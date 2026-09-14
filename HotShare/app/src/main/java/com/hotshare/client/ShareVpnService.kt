package com.hotshare.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.hotshare.core.Constants
import hev.htproxy.TProxyService
import java.io.File
import java.net.InetAddress

/**
 * v1.1: real tun2socks via hev-socks5-tunnel (JNI .so).
 * Routes ALL TCP+UDP (incl. DNS, QUIC) through the host's SOCKS5 :1080.
 * The proxy path itself is excluded from the tun (excludeRoute, API 33+)
 * and the process is pinned to the hotspot network by JoinActivity.
 */
class ShareVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.hotshare.client.CONNECT"
        const val ACTION_STOP = "com.hotshare.client.STOP"
        const val EXTRA_IP = "ip"
        const val EXTRA_SOCKS = "socks"
        const val EXTRA_TOKEN = "token"
        const val TUN_ADDR = "198.18.0.1"
        const val MTU = Constants.TUN_MTU

        @Volatile var pinnedNetwork: Network? = null
        @Volatile var isRunning = false
        /** Reason the tunnel last stopped; JoinActivity shows it on return. */
        @Volatile var lastStopReason = ""
        /** Live monitor state for the UI + notification. */
        @Volatile var connectedHost: String = ""
        @Volatile var connectedSince: Long = 0L
        @Volatile var upBps: Long = 0L
        @Volatile var downBps: Long = 0L
        @Volatile var upTotal: Long = 0L
        @Volatile var downTotal: Long = 0L
        const val NOTIF_ID = 100
        const val ACTION_DISCONNECT = ACTION_STOP

        fun uptimeMs(): Long =
            if (connectedSince > 0L) System.currentTimeMillis() - connectedSince else 0L

        fun resetMonitor() {
            connectedHost = ""; connectedSince = 0L
            upBps = 0; downBps = 0; upTotal = 0; downTotal = 0
        }
        // Specifier Wi-Fi request owned by the SERVICE so EVERY exit path
        // (button, onRevoke, onDestroy, hotspot loss) releases it.
        @Volatile private var wifiCm: ConnectivityManager? = null
        @Volatile private var wifiCallback: ConnectivityManager.NetworkCallback? = null
        // Service-side watchdog on the underlying hotspot network (already-connected
        // path has no Activity specifier callback, so without this a dead hotspot
        // leaves the tun up blackholing everything).
        @Volatile private var watchCm: ConnectivityManager? = null
        @Volatile private var watchCallback: ConnectivityManager.NetworkCallback? = null
        // Generation guard: an old async shutdown must never wipe a newer tunnel's
        // pin/wifi/bind. Incremented on every start AND every shutdown.
        private val sessionGen = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile private var stopRequested = false

        fun attachWifi(cm: ConnectivityManager, cb: ConnectivityManager.NetworkCallback) {
            releaseWifi()
            wifiCm = cm
            wifiCallback = cb
        }

        fun releaseWifi() {
            val cm = wifiCm
            val cb = wifiCallback
            wifiCallback = null
            wifiCm = null
            try { if (cm != null && cb != null) cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }

        internal fun releaseWatch() {
            val cm = watchCm
            val cb = watchCallback
            watchCallback = null
            watchCm = null
            try { if (cm != null && cb != null) cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }

        fun start(ctx: Context, ip: String, socks: Int, token: String = "") {
            stopRequested = false
            val i = Intent(ctx, ShareVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_IP, ip)
                .putExtra(EXTRA_SOCKS, socks)
                .putExtra(EXTRA_TOKEN, token)
            try {
                ctx.startService(i)
            } catch (_: Exception) {
                // Background-start blocked (Android 8+): fall back so the
                // service still gets the intent when allowed.
                try { androidx.core.content.ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) {}
            }
        }

        fun stop(ctx: Context) {
            stopRequested = true
            // Invalidate any in-flight startTunnel() so its post-checks abort.
            sessionGen.incrementAndGet()
            val i = Intent(ctx, ShareVpnService::class.java).setAction(ACTION_STOP)
            var delivered = false
            try { ctx.startService(i); delivered = true } catch (_: Exception) {}
            if (!delivered) {
                // Could not deliver STOP (background restrictions / dead process):
                // force-kill via stopService so onDestroy() runs the same teardown.
                try { ctx.stopService(Intent(ctx, ShareVpnService::class.java)) } catch (_: Exception) {}
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    // Single-owner fd: detachFd() hands ownership to native; adoptFd().close()
    // must run EXACTLY once or libc SIGABRTs (seen 15:53 Thread-22 shutdownSync+118).
    // getAndSet(-1) guarantees exactly one closer wins across racing paths.
    private val tunFd = java.util.concurrent.atomic.AtomicInteger(-1)
    private val fdLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val gen = sessionGen.incrementAndGet()
                // Fast async teardown, then kill service. NEVER sticky: a lingering
                // restart would re-create a blackholing tun with no config.
                Thread { shutdownSync("disconnected", gen); stopSelf() }.also { it.isDaemon = true }.start()
                return START_NOT_STICKY
            }
            ACTION_CONNECT, null -> {
                if (intent == null) {
                    // System restarted us (sticky kill) with no config: do NOT
                    // resurrect a tun — just clean up and die so net works.
                    val gen = sessionGen.incrementAndGet()
                    Thread { shutdownSync("restarted without config", gen); stopSelf() }.also { it.isDaemon = true }.start()
                    return START_NOT_STICKY
                }
            }
            else -> {}
        }
        val ip = intent?.getStringExtra(EXTRA_IP) ?: return START_NOT_STICKY
        val socks = intent.getIntExtra(EXTRA_SOCKS, Constants.SOCKS_PORT)
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (isRunning || worker != null) {
            // Re-connect with new target: stop old tunnel first (async), then start.
            // Generation guard prevents the old shutdown from wiping the new pin.
            val oldGen = sessionGen.incrementAndGet()
            stopRequested = false
            Thread {
                shutdownSync("reconnecting", oldGen)
                if (stopRequested) { stopSelf(); return@Thread }
                startTunnel(ip, socks, token)
            }.also { it.isDaemon = true }.start()
            return START_NOT_STICKY
        }
        lastStopReason = ""
        stopRequested = false
        // NEVER do the preflight/establish work on the main thread: probeHostReachable
        // opens sockets and NetworkOnMainThreadException would abort it silently.
        Thread { startTunnel(ip, socks, token) }.also { it.isDaemon = true }.start()
        // NOT_STICKY: if the process dies, Android must NOT restart us with a
        // null intent while the UI thinks we're connected — that leaves a zombie
        // VPN + "disconnect showing, net dead". User taps Connect to re-establish.
        return START_NOT_STICKY
    }

    /** System tore the VPN down outside our UI (Settings → VPN → Disconnect,
     *  always-on conflict, profile removed). Clean everything like a Disconnect tap. */
    override fun onRevoke() {
        val gen = sessionGen.incrementAndGet()
        Thread { shutdownSync("stopped by system (VPN revoked)", gen); stopSelf() }.also { it.isDaemon = true }.start()
    }

    private fun startTunnel(ip: String, socks: Int, token: String = "") {
        val myGen = sessionGen.incrementAndGet()
        if (stopRequested) return

        // Ensure all our (and the native lib's) sockets ride the hotspot network.
        // Fall back to the active network (already-connected path) so the SOCKS
        // uplink never goes through our own tun (routing loop). This works on
        // BOTH 2.4GHz and 5GHz hotspot bands — the network handle is band-agnostic.
        // Pre-API33 has no excludeRoute: process binding + underlying network is
        // the loop protection (native sockets inherit the process default network).
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = pinnedNetwork ?: cm.activeNetwork
        pinnedNetwork = net
        net?.let {
            try { cm.bindProcessToNetwork(it) } catch (_: Exception) {}
        }
        if (stopRequested || sessionGen.get() != myGen) {
            // A STOP raced us: abort before establishing a zombie tun.
            if (sessionGen.get() == myGen) cleanupAfterTunnel(myGen, "cancelled")
            return
        }

        // Pre-flight: TCP to host:1080 BEFORE establish(). Without this a stale QR
        // (hotspot IP changes every toggle: .87 -> .250 -> 10.192.x) builds a
        // 0.0.0.0/0 tun to a dead SOCKS = instant blackhole + "tunnel exited".
        // Fail fast with an actionable reason instead.
        // No-proxy devices: this VPN path needs NO per-network proxy setting —
        // all TCP+UDP (incl. DNS/QUIC/games) rides the tun transparently.
        val preflightErr = probeHostReachable(ip, socks, token, net)
        if (preflightErr != null) {
            android.util.Log.e("HotShare", "preflight failed: $preflightErr (net=$net)")
            lastStopReason = "host unreachable $ip:$socks ($preflightErr) — re-scan host QR"
            cleanupAfterTunnel(myGen, lastStopReason)
            stopSelf()
            return
        }
        android.util.Log.i("HotShare", "preflight OK to $ip:$socks via net=$net")
        val builder = Builder()
            .setSession("HotShare tun2socks")
            .setMtu(MTU)
            .addAddress(TUN_ADDR, 32)
            .addRoute("0.0.0.0", 0)
            // Sinkhole IPv6: the host proxy is IPv4-only. Without this route,
            // IPv6-capable apps would send traffic outside the tunnel.
            .addRoute("::", 0)
            .addDnsServer(Constants.DNS_PRIMARY)
            .addDnsServer(Constants.DNS_SECONDARY)
            .setBlocking(false)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, JoinActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        // Tell the system which physical network carries the tunnel (metering, teardown).
        net?.let { try { builder.setUnderlyingNetworks(arrayOf(it)) } catch (_: Exception) {} }
        // Keep the proxy connection OUT of the tun (prevents routing loop).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName(ip), 32)) } catch (_: Exception) {}
        }
        // Pre-API33 has no excludeRoute: protect the uplink explicitly via binding
        // (done above) + underlying network. Also protect our own future sockets.
        tun = try { builder.establish() } catch (_: Exception) { null }
        if (tun == null) {
            android.util.Log.e("HotShare", "establish() returned null")
            lastStopReason = "vpn establish failed"
            cleanupAfterTunnel(myGen, "vpn establish failed")
            stopSelf()
            return
        }
        android.util.Log.i("HotShare", "tun established; fd=${tun!!.fd}")
        if (stopRequested || sessionGen.get() != myGen) {
            // STOP arrived while establish() blocked: tear down immediately.
            // detachFd() transfers ownership; after that tun.close() would
            // double-close -> SIGABRT. Close exactly one of them.
            var fdTmp = -1
            val t = synchronized(fdLock) {
                val cur = tun; tun = null; tunFd.set(-1); cur
            }
            try { fdTmp = t?.detachFd() ?: -1 } catch (_: Exception) {}
            try { if (fdTmp != -1) ParcelFileDescriptor.adoptFd(fdTmp).close() } catch (_: Exception) {}
            try { if (fdTmp == -1) t?.close() } catch (_: Exception) {}
            cleanupAfterTunnel(myGen, "cancelled")
            stopSelf()
            return
        }

        val detached = try { tun!!.detachFd() } catch (_: Exception) { -1 }
        // detachFd() invalidates tun: drop our ref WITHOUT close() (else
        // double-close SIGABRT like 15:53 Thread-22). Ownership -> native fd.
        synchronized(fdLock) { tun = null; tunFd.set(detached) }
        // Extra safety on old APIs: protect the tun fd itself is NOT needed, but
        // make sure the native SOCKS uplink can't loop: already bound via process.
        val cfg = writeConfig(ip, socks, token)
        // Gracefully handle missing native lib (sideload without jniLibs):
        // surface a clear reason instead of a silent "tunnel exited".
        if (!TProxyService.isAvailable) {
            lastStopReason = "tun2socks lib missing — use system-proxy mode"
            cleanupAfterTunnel(myGen, lastStopReason)
            try { ParcelFileDescriptor.adoptFd(tunFd.getAndSet(-1)).close() } catch (_: Exception) {}
            stopSelf()
            return
        }
        isRunning = true
        registerWatchdog(cm, net)
        connectedHost = "$ip:$socks"
        connectedSince = System.currentTimeMillis()
        startForegroundCompat(buildVpnNotification())
        Thread { monitorLoop() }.also { it.isDaemon = true }.start()
        worker = Thread {
            android.util.Log.i("HotShare", "tun2socks starting: fd=${tunFd.get()} cfg=${cfg.absolutePath}")
            // TProxyStartService is NON-BLOCKING: it spawns the native tunnel
            // thread and returns true (false = config/fd rejected). Do NOT treat
            // the return as "tunnel exited" — poll the native liveness instead.
            val started = try {
                TProxyService.startService(cfg.absolutePath, tunFd.get())
            } catch (t: Throwable) {
                android.util.Log.e("HotShare", "tun2socks start threw", t)
                false
            }
            if (!started) {
                android.util.Log.e("HotShare", "tun2socks start returned false (bad config/fd)")
                if (sessionGen.get() == myGen && !stopRequested) {
                    shutdownSync("tun2socks start failed", myGen)
                    stopSelf()
                }
                return@Thread
            }
            android.util.Log.i("HotShare", "tun2socks native running")
            // Liveness loop: detect native death (host gone, lib crash) so the
            // tun never blackholes. Exits when a stop is requested (gen changes).
            try {
                while (!stopRequested && sessionGen.get() == myGen) {
                    Thread.sleep(1000)
                    if (sessionGen.get() != myGen || stopRequested) break
                    if (!TProxyService.isRunning()) {
                        android.util.Log.w("HotShare", "tun2socks native exited on its own")
                        isRunning = false
                        shutdownSync("tunnel exited (host unreachable?)", myGen)
                        stopSelf()
                        return@Thread
                    }
                }
            } catch (_: InterruptedException) {}
            android.util.Log.i("HotShare", "tun2socks worker loop ended")
        }.also { it.start() }
    }

    /** Watch the underlying hotspot network: if it truly vanishes, self-destruct so
     *  the phone is immediately free (no zombie pin, no blackholing tun).
     *  IMPORTANT: do NOT use registerDefaultNetworkCallback — when our VPN becomes
     *  the default network the framework delivers onLost for the *previous* default
     *  (the hotspot WiFi), which would kill a perfectly healthy tunnel. Instead we
     *  watch WiFi networks and only tear down if the pinned network is gone from
     *  allNetworks. */
    private fun registerWatchdog(cm: ConnectivityManager, net: Network?) {
        releaseWatch()
        if (net == null) return
        try {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(n: Network) {
                    val pinned = pinnedNetwork ?: return
                    if (n.networkHandle != pinned.networkHandle) return
                    // Default-network churn: the network object still exists (it is
                    // merely no longer the default) — ignore.
                    val stillThere = try {
                        cm.allNetworks.any { it.networkHandle == pinned.networkHandle }
                    } catch (_: Exception) { false }
                    if (stillThere) return
                    val gen = sessionGen.incrementAndGet()
                    Thread { shutdownSync("hotspot lost", gen); stopSelf() }.also { it.isDaemon = true }.start()
                }
                override fun onUnavailable() {}
            }
            val req = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, cb)
            watchCm = cm; watchCallback = cb
        } catch (_: Exception) {}
    }

    private fun startForegroundCompat(notif: Notification) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
            try { startForeground(NOTIF_ID, notif) } catch (_: Exception) {}
        }
    }

    /** Refresh the VPN notification + UI monitor state once per second using the
     *  native tun2socks stats: [tx_packets, tx_bytes, rx_packets, rx_bytes]. */
    private fun monitorLoop() {
        val mgr = getSystemService(NotificationManager::class.java)
        var lastUp = 0L; var lastDown = 0L; var lastT = 0L
        while (isRunning) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            if (!isRunning) break
            val s = try { TProxyService.getStats() } catch (_: Throwable) { LongArray(0) }
            val up = if (s.size > 1) s[1] else 0L      // client -> host (upload)
            val down = if (s.size > 3) s[3] else 0L    // host -> client (download)
            val now = System.currentTimeMillis()
            if (lastT != 0L) {
                val dt = (now - lastT).coerceAtLeast(1L)
                upBps = ((up - lastUp) * 1000L) / dt
                downBps = ((down - lastDown) * 1000L) / dt
            }
            upTotal = up; downTotal = down
            lastUp = up; lastDown = down; lastT = now
            try { mgr.notify(NOTIF_ID, buildVpnNotification()) } catch (_: Exception) {}
        }
    }

    private fun buildVpnNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel("vpn", "HotShare VPN", NotificationManager.IMPORTANCE_LOW)
        )
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, JoinActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = android.app.PendingIntent.getService(
            this, 1,
            Intent(this, ShareVpnService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Disconnect", stop
        ).build()
        val text = if (isRunning)
            "$connectedHost • ↓${fmtSpeed(downBps)} ↑${fmtSpeed(upBps)} • ${fmtDuration(uptimeMs())}"
        else "Disconnected"
        return Notification.Builder(this, "vpn")
            .setContentTitle(if (isRunning) "HotShare VPN — Connected" else "HotShare VPN")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(action)
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

    private fun fmtDuration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    private fun writeConfig(hostIp: String, socksPort: Int, token: String = ""): File {
        val cfg = File(filesDir, "hotshare-tun.yml")
        val log = File(filesDir, "hev.log").absolutePath
        // hev-socks5-tunnel requires BOTH username+password when auth is used.
        val authBlock = if (token.isNotBlank()) "\n              username: '${Constants.AUTH_USER}'\n              password: '$token'" else ""
        // NOTE: tunnel.ipv4/ipv6 are SCALAR addresses in hev's schema, e.g.
        //   tunnel:\n  ipv4: 198.18.0.1
        // A nested map (ipv4:\n  address: ...) fails config parsing and the
        // native tunnel exits immediately ("Failed to parse config").
        cfg.writeText(
            """
            tunnel:
              mtu: $MTU
              ipv4: $TUN_ADDR
            socks5:
              address: '$hostIp'
              port: $socksPort$authBlock
              udp: 'udp'
            misc:
              log-file: '$log'
              log-level: 'debug'
            """.trimIndent()
        )
        android.util.Log.i("HotShare", "tun config:\n" + cfg.readText())
        return cfg
    }

    /**
     * Full teardown: signal native, CLOSE THE NATIVE FD EXACTLY ONCE
     * (getAndSet(-1) — concurrent shutdownSync/onDestroy paths racing here
     * caused the 15:53 SIGABRT double-close), then join worker, unbind.
     * Generation-guarded: a stale shutdown never clears a newer tunnel's state.
     * Safe to call repeatedly and from any thread.
     */
    private fun shutdownSync(reason: String = "disconnected", gen: Int = sessionGen.get()) {
        val isCurrent = (sessionGen.get() == gen)
        if (isCurrent) isRunning = false
        if (reason != "reconnecting" && isCurrent) lastStopReason = reason
        val w = synchronized(fdLock) { worker }
        // Exactly one thread wins the native fd; losers get -1 and close nothing.
        val fd = tunFd.getAndSet(-1)
        // Drop the (already-detached, invalid) ParcelFileDescriptor ref WITHOUT
        // close() — it was invalidated by detachFd() in startTunnel().
        synchronized(fdLock) { tun = null }
        try { TProxyService.stopService() } catch (_: Throwable) {}
        // Close native fd FIRST so the system tears down the tun route at once —
        // every second we wait with 0.0.0.0/0 → dead SOCKS is "net not working".
        if (fd != -1) {
            try { ParcelFileDescriptor.adoptFd(fd).close() } catch (_: Exception) {}
        }
        try { TProxyService.stopService() } catch (_: Throwable) {}
        // Join the worker, but never join ourselves (the liveness loop can call
        // shutdownSync on its own thread — joining self would stall).
        if (w != null && w !== Thread.currentThread()) {
            try { w.join(2500) } catch (_: Exception) {}
        }
        if (w != null && w !== Thread.currentThread()) {
            try { w.interrupt() } catch (_: Exception) {}
        }
        synchronized(fdLock) { if (worker === w) worker = null }
        if (isCurrent) {
            try { stopForeground(true) } catch (_: Exception) {}
            resetMonitor()
            cleanupAfterTunnel(gen, reason)
        }
    }

    /**
     * Pre-flight reachability: plain TCP to host:socks on the HOTSPOT network
     * (bound socket — never through the tun, which doesn't exist yet).
     * Handles both open and token-authed hosts (offers 0x00+0x02, completes
     * RFC1929 when the server picks 0x02). Returns null when reachable.
     * Must run BEFORE Builder.establish(), while process is bound to `net`.
     */
    private fun probeHostReachable(hostIp: String, socksPort: Int, token: String, net: Network?): String? {
        return try {
            val sock = (net?.socketFactory?.createSocket()
                ?: javax.net.SocketFactory.getDefault().createSocket()) as java.net.Socket
            try {
                sock.tcpNoDelay = true
                sock.connect(java.net.InetSocketAddress(hostIp, socksPort), 2000)
                sock.soTimeout = 2000
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()
                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02)); out.flush()
                val b0 = try { inp.read() } catch (_: Exception) { -1 }
                val b1 = try { inp.read() } catch (_: Exception) { -1 }
                if (b0 != 0x05) return "socks greeting rejected"
                when (b1) {
                    0x00 -> null // open host
                    0x02 -> {
                        // Server wants user/pass — complete subnegotiation.
                        if (token.isBlank()) return "host requires token — re-scan QR"
                        val u = Constants.AUTH_USER.toByteArray()
                        val p = token.toByteArray()
                        val req = byteArrayOf(0x01, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p
                        out.write(req); out.flush()
                        val r0 = try { inp.read() } catch (_: Exception) { -1 }
                        val r1 = try { inp.read() } catch (_: Exception) { -1 }
                        if (r0 == 0x01 && r1 == 0x00) null else "token rejected — re-scan QR"
                    }
                    else -> "socks greeting rejected"
                }
            } catch (e: java.net.SocketTimeoutException) {
                "connect timeout (wrong IP? hotspot changed?)"
            } catch (e: java.net.ConnectException) {
                "connection refused (host app stopped?)"
            } catch (e: java.net.NoRouteToHostException) {
                "no route (not on hotspot?)"
            } catch (e: java.net.UnknownHostException) {
                "bad IP $hostIp"
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            } finally {
                try { sock.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        }
    }

    /** Clear pin/bind/wifi ONLY if no newer session started (gen still current). */
    private fun cleanupAfterTunnel(gen: Int, @Suppress("UNUSED_PARAMETER") reason: String) {
        if (sessionGen.get() != gen) return
        pinnedNetwork = null
        // Release the specifier Wi-Fi request FIRST so the radio is free for any
        // other network the moment the tunnel is gone, then unbind the process.
        releaseWifi()
        releaseWatch()
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .bindProcessToNetwork(null)
        } catch (_: Exception) {}
    }

    private fun shutdown() = shutdownSync()

    override fun onDestroy() {
        val gen = sessionGen.incrementAndGet()
        // onDestroy runs on the main thread: keep teardown short to avoid ANR.
        // fd close is immediate so routing is restored even if native lingers.
        shutdownSync(if (lastStopReason.isBlank()) "destroyed" else lastStopReason, gen)
        super.onDestroy()
    }
}
