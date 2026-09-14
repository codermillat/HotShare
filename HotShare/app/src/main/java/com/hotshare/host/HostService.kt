package com.hotshare.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.hotshare.core.Constants
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** No-root Host: HTTP CONNECT :8080 + SOCKS5 :1080 on the hotspot AP interface.
 * Binds to ap0's IP (dual-band safe: works on 2.4GHz + 5GHz subnets) and only
 * accepts hotspot-subnet clients. Access control = AP-IP bind + subnet allowlist
 * (the hotspot WPA2 password). Optional per-session token (QR) adds strict auth
 * when [EXTRA_REQUIRE_AUTH] is set; the app's VPN client always sends it. */
class HostService : Service() {

    private val running = AtomicBoolean(false)
    @Volatile private var pool = Executors.newCachedThreadPool()
    private var httpServer: ServerSocket? = null
    private var socksServer: ServerSocket? = null
    @Volatile private var authToken: String = ""
    @Volatile private var requireAuth: Boolean = false
    @Volatile private var bindIp: String = ""

    companion object {
        const val ACTION_START = "com.hotshare.host.START"
        const val ACTION_STOP = "com.hotshare.host.STOP"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_REQUIRE_AUTH = "requireAuth"
        const val NOTIF_ID = 1
        @Volatile var isRunning = false; private set
        @Volatile var boundAddress: String = ""; private set
        /** Token/flag of the RUNNING service — lets the host UI rebuild a correct
         * QR after an Activity/process recreation (otherwise the QR shows an
         * empty token while the service still enforces the original one). */
        @Volatile var activeToken: String = ""; private set
        @Volatile var activeRequireAuth: Boolean = false; private set
        fun start(ctx: Context, token: String = "", requireAuth: Boolean = false) {
            val i = Intent(ctx, HostService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_REQUIRE_AUTH, requireAuth)
            ctx.startForegroundService(i)
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, HostService::class.java).setAction(ACTION_STOP))
        }

        /** Allowlist: hotspot/AP subnets + loopback (for on-device tests). */
        fun isAllowedClient(clientIp: String, apIp: String): Boolean {
            if (clientIp.startsWith("127.")) return true
            if (clientIp == apIp) return true
            if (clientIp.startsWith("192.168.") || clientIp.startsWith("10.") ||
                clientIp.startsWith("172.16.") || clientIp.startsWith("172.17.") ||
                clientIp.startsWith("172.18.") || clientIp.startsWith("172.19.") ||
                clientIp.startsWith("172.2") || clientIp.startsWith("172.30.") ||
                clientIp.startsWith("172.31.")) {
                // Same /16 as the AP IP is the hotspot client for sure; other
                // private ranges are allowed because OEM subnets vary
                // (TECNO 10.128.181.x, stock 192.168.43.x, etc.).
                return true
            }
            // Same first two octets as AP (covers OEM /16 variants).
            val apPrefix = apIp.substringBeforeLast(".", missingDelimiterValue = "")
                .substringBeforeLast(".", missingDelimiterValue = "")
            if (apPrefix.isNotBlank() && clientIp.startsWith(apPrefix)) return true
            return false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); stopSelf(); return START_NOT_STICKY }
            else -> {
                authToken = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
                requireAuth = intent?.getBooleanExtra(EXTRA_REQUIRE_AUTH, false) ?: false
                activeToken = authToken
                activeRequireAuth = requireAuth
                ensureStarted()
            }
        }
        return START_STICKY
    }

    private fun ensureStarted() {
        if (running.getAndSet(true)) return
        if (pool.isShutdown || pool.isTerminated) pool = Executors.newCachedThreadPool()
        // Bind to the AP interface IP so the proxy rides whichever band/subnet
        // the hotspot uses (2.4GHz vs 5GHz, 192.168.43.x vs 10.128.181.x).
        bindIp = HotspotHelper.getApIp() ?: ""
        boundAddress = bindIp
        HostStats.reset()
        startForegroundCompat(NOTIF_ID, buildNotification())
        isRunning = true
        pool.execute { runHttp() }
        pool.execute { runSocks() }
        pool.execute { notifierLoop() }
    }

    /** Refresh the "sharing" notification every 2 s with clients + live speed. */
    private fun notifierLoop() {
        val mgr = getSystemService(NotificationManager::class.java)
        while (running.get()) {
            try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            if (!running.get()) break
            try { HostStats.sample() } catch (_: Exception) {}
            try { mgr.notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
        }
    }

    private fun startForegroundCompat(id: Int, notif: Notification) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // Must match the manifest's declared foregroundServiceType.
                startForeground(
                    id, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(id, notif)
            }
        } catch (_: Exception) {
            try { startForeground(id, notif) } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel("hotshare", "HotShare", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, com.hotshare.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val clients = HostStats.activeClients()
        val where = if (bindIp.isNotBlank()) "$bindIp:${Constants.HTTP_PORT}" else "port ${Constants.HTTP_PORT}"
        val text = if (clients == 0)
            "Waiting for clients • $where"
        else
            "$clients client(s) • ↓${fmtSpeed(HostStats.downBps)} ↑${fmtSpeed(HostStats.upBps)}"
        return Notification.Builder(this, "hotshare")
            .setContentTitle("HotShare — Sharing")
            .setContentText(text)
            .setSubText("HTTP ${Constants.HTTP_PORT} • SOCKS ${Constants.SOCKS_PORT}")
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(com.hotshare.R.drawable.ic_stat_hotshare)
            .build()
    }

    private fun fmtSpeed(bps: Long): String {
        val v = if (bps < 0) 0 else bps
        return when {
            v < 1024 -> "$v B/s"
            v < 1024 * 1024 -> String.format("%.1f KB/s", v / 1024.0)
            else -> String.format("%.2f MB/s", v / (1024.0 * 1024.0))
        }
    }

    private fun bindServer(port: Int): ServerSocket {
        val s = java.net.ServerSocket()
        s.reuseAddress = true
        val apIp = bindIp
        if (apIp.isNotBlank()) {
            try {
                s.bind(InetSocketAddress(java.net.InetAddress.getByName(apIp), port))
                return s
            } catch (_: Exception) {
                // AP IP vanished between discovery and bind (toggle race) — fall through.
            }
        }
        s.bind(InetSocketAddress(port))
        return s
    }

    private fun allowed(client: Socket): Boolean {
        return try {
            val remote = client.remoteSocketAddress as? InetSocketAddress ?: return false
            val ip = remote.address.hostAddress ?: ""
            val apIp = bindIp.ifBlank { HotspotHelper.getApIp() ?: "" }
            val ok = isAllowedClient(ip, apIp)
            if (ok) HostStats.markClient(ip)
            ok
        } catch (_: Exception) { false }
    }

    private fun runHttp() {
        try {
            bindServer(Constants.HTTP_PORT).also { httpServer = it }.use { server ->
                while (running.get()) {
                    val client = try { server.accept() } catch (_: Exception) { break }
                    if (!allowed(client)) { try { client.close() } catch (_: Exception) {} ; continue }
                    val token = authToken
                    val strict = requireAuth
                    pool.execute { HttpRelay.handle(client, token, strict) }
                }
            }
        } catch (_: Exception) {}
    }

    private fun runSocks() {
        try {
            val relay = SocksRelay(running, authTokenProvider = { authToken })
            bindServer(Constants.SOCKS_PORT).also { socksServer = it }.use { server ->
                server.soTimeout = 1000
                while (running.get()) {
                    val client = try { server.accept() }
                    catch (e: java.net.SocketTimeoutException) { continue }
                    catch (_: Exception) { break }
                    if (!allowed(client)) { try { client.close() } catch (_: Exception) {} ; continue }
                    // Refresh token per connection (rotated on re-share without restart).
                    val token = authToken
                    val strict = requireAuth
                    pool.execute { relay.handleSingle(client, token, strict) }
                }
            }
        } catch (_: Exception) {}
    }

    private fun shutdown() {
        running.set(false); isRunning = false
        boundAddress = ""
        activeToken = ""
        activeRequireAuth = false
        try { HostStats.reset() } catch (_: Exception) {}
        try { httpServer?.close() } catch (_: Exception) {}
        try { socksServer?.close() } catch (_: Exception) {}
        httpServer = null; socksServer = null
        pool.shutdownNow()
    }
}
