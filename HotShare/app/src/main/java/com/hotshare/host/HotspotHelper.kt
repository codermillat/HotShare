package com.hotshare.host

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.hotshare.core.Constants
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/** Host helpers: LocalOnlyHotspot (no-root auto AP) + local IP discovery.
 * Dual-band: works on 2.4GHz AND 5GHz. LOHS band is forced via
 * SoftApConfiguration on API 34+, otherwise the system picks the band and we
 * report it so the user can switch the manual hotspot band in Settings. */
object HotspotHelper {

    // SoftApConfiguration band flags (AOSP values). Declared locally because
    // some android.jar distributions strip these constants from the stubs.
    private const val BAND_2GHZ = 1
    private const val BAND_5GHZ = 2

    /** AP band preference for [start]. AUTO lets the system decide (most compatible). */
    enum class ApBand(val label: String) {
        AUTO("Auto (recommended)"),
        GHZ_2("2.4 GHz (max compatibility)"),
        GHZ_5("5 GHz (max speed)")
    }

    /** Which access point to use for sharing.
     *  - AUTO: try the app's temporary hotspot, fall back to the system hotspot.
     *  - TEMPORARY: app-created LocalOnlyHotspot (random SSID/pass, no setup).
     *  - SYSTEM: the phone's normal "Mobile hotspot" (user-configured SSID/pass/band). */
    enum class ApMode(val label: String) {
        AUTO("Auto"),
        TEMPORARY("Temporary (app)"),
        SYSTEM("Mobile hotspot (system)")
    }

    data class ApInfo(
        val ssid: String,
        val pass: String,
        val ip: String,
        val bandLabel: String = "",
        val staLabel: String = ""
    )

    /** Null = LOHS not started. Non-null reason when start fails (for UI fallback). */
    @Volatile var lastFailReason: Int = -1
        private set

    /** Band the user asked for; used only when the actual channel is unknown. */
    @Volatile private var requestedBand: ApBand = ApBand.AUTO

    private val reservation = AtomicReference<WifiManager.LocalOnlyHotspotReservation?>(null)

    /** Start LocalOnlyHotspot; callback on main thread. Needs CHANGE_WIFI_STATE + fine location.
     * @param band 2.4/5GHz preference. Honored on API 34+ via SoftApConfiguration;
     * on older APIs the system picks the band (both bands still work — the proxy
     * binds to ap0's IP regardless of subnet) and [ApInfo.bandLabel] reports it. */
    @SuppressLint("MissingPermission")
    fun start(activity: Activity, band: ApBand = ApBand.AUTO, onReady: (ApInfo?) -> Unit) {
        requestedBand = band
        val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            val handler = Handler(Looper.getMainLooper())
            val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation.set(res)
                    // ap0's IPv4 is often assigned AFTER onStarted fires; wait briefly
                    // for it, otherwise we'd snapshot the STA (wlan0) IP into the QR.
                    Thread {
                        var info: ApInfo? = null
                        val deadline = System.currentTimeMillis() + 10000
                        while (System.currentTimeMillis() < deadline) {
                            if (getApIp() != null) {
                                info = extract(res)
                                break
                            }
                            try { Thread.sleep(300) } catch (_: Exception) { break }
                        }
                        // STRICT: never fall back to wlan0 STA IP — a QR with the
                        // STA IP is unreachable for clients (stale-IP "tunnel
                        // exited" blackhole). ap0-only, or null -> system fallback.
                        val finalInfo = info ?: extractStrict(res)
                        handler.post { onReady(finalInfo) }
                    }.also { it.isDaemon = true }.start()
                }
                override fun onFailed(reason: Int) {
                    lastFailReason = reason
                    handler.post { onReady(null) }
                }
                override fun onStopped() {
                    reservation.set(null)
                }
            }
            wm.startLocalOnlyHotspot(cb, handler)
        } catch (e: Exception) {
            onReady(null)
        }
    }

    /**
     * Band-aware start with fallback chain:
     * 1. API 34+: try SoftApConfiguration with the requested band first.
     * 2. Legacy startLocalOnlyHotspot (any band — both work).
     * Kept separate so callers on old APIs don't pay the reflection cost.
     */
    @SuppressLint("MissingPermission")
    fun startWithBandFallback(
        activity: Activity,
        band: ApBand,
        onReady: (ApInfo?) -> Unit
    ) {
        requestedBand = band
        if (band == ApBand.AUTO || android.os.Build.VERSION.SDK_INT < 34) {
            start(activity, band, onReady)
            return
        }
        if (tryBandSpecificHotspot(activity, band, onReady)) return
        // Band-specific request rejected (driver/channel conflict, e.g. STA 5GHz +
        // AP 5GHz on a single radio) → fall back to system-picked band. Both work.
        start(activity, ApBand.AUTO, onReady)
    }

    /** API 34+ only: request a specific AP band. Returns false when unavailable.
     * Uses reflection because some android.jar distributions strip
     * SoftApConfiguration.Builder from the compile-time stubs. */
    @SuppressLint("MissingPermission", "NewApi")
    private fun tryBandSpecificHotspot(
        activity: Activity,
        band: ApBand,
        onReady: (ApInfo?) -> Unit
    ): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT < 34) return false
            val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val softBand = when (band) {
                ApBand.GHZ_2 -> BAND_2GHZ
                ApBand.GHZ_5 -> BAND_5GHZ
                else -> return false
            }
            // LOHS generates its own SSID/pass; we only pin the band.
            val builderCls = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val builder = builderCls.getConstructor().newInstance()
            builderCls.getMethod("setBand", Int::class.javaPrimitiveType).invoke(builder, softBand)
            val config = builderCls.getMethod("build").invoke(builder)
                ?: return false
            val handler = Handler(Looper.getMainLooper())
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation.set(res)
                    Thread {
                        var info: ApInfo? = null
                        val deadline = System.currentTimeMillis() + 10000
                        while (System.currentTimeMillis() < deadline) {
                            if (getApIp() != null) { info = extract(res); break }
                            try { Thread.sleep(300) } catch (_: Exception) { break }
                        }
                        val finalInfo = info ?: extractStrict(res)
                        handler.post { onReady(finalInfo) }
                    }.also { it.isDaemon = true }.start()
                }
                override fun onFailed(reason: Int) {
                    lastFailReason = reason
                    handler.post { onReady(null) }
                }
                override fun onStopped() { reservation.set(null) }
            }
            // WifiManager.startLocalOnlyHotspot(SoftApConfiguration, Executor, Callback)
            val m = WifiManager::class.java.getMethod(
                "startLocalOnlyHotspot",
                config.javaClass,
                java.util.concurrent.Executor::class.java,
                WifiManager.LocalOnlyHotspotCallback::class.java
            )
            m.invoke(wm, config, executor, cb)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** STA (ISP uplink) frequency in MHz, or null when unknown. */
    @Suppress("DEPRECATION")
    fun getStaFrequencyMhz(ctx: Context): Int? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val freq = wm.connectionInfo?.frequency ?: 0
            if (freq > 0) freq else null
        } catch (_: Exception) { null }
    }

    /** Human STA band: "2.4 GHz", "5 GHz", "6 GHz", or "unknown". */
    fun staBandLabel(ctx: Context): String {
        val freq = getStaFrequencyMhz(ctx) ?: return "unknown"
        return when {
            freq in 2400..2500 -> "2.4 GHz ($freq MHz)"
            freq in 4900..5900 -> "5 GHz ($freq MHz)"
            freq >= 5900 -> "6 GHz ($freq MHz)"
            else -> "$freq MHz"
        }
    }

    /** True when the device can keep STA (ISP) + AP (hotspot) up at once. */
    fun isStaApConcurrencySupported(ctx: Context): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                // Hidden on some OEMs — reflect, default to true (manual-hotspot fallback covers false).
                try {
                    val m = wm.javaClass.getMethod("isStaApConcurrencySupported")
                    (m.invoke(wm) as? Boolean) ?: true
                } catch (_: Exception) { true }
            } else true
        } catch (_: Exception) { true }
    }

    /** Hint shown under the band selector (5+5 single-radio conflict, etc.). */
    fun bandHint(ctx: Context, band: ApBand): String {
        val sta = staBandLabel(ctx)
        val concurrent = isStaApConcurrencySupported(ctx)
        return when (band) {
            ApBand.AUTO -> "ISP Wi-Fi: $sta. Auto lets Android pick a conflict-free channel — works on both bands."
            ApBand.GHZ_2 -> "Forces AP 2.4 GHz (API 34+; older falls back to Auto). " +
                "Best when ISP is 5 GHz ($sta) or clients are old/far. Max compatibility."
            ApBand.GHZ_5 -> "Forces AP 5 GHz (API 34+; older falls back to Auto). " +
                "Fastest when ISP is 2.4 GHz. May fail on single-radio phones when ISP is also 5 GHz ($sta)" +
                if (concurrent) "." else " — this device may not support STA+AP concurrency; use USB-tether + proxy then."
        }
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun extract(res: WifiManager.LocalOnlyHotspotReservation): ApInfo? {
        // LocalOnlyHotspotReservation exposes only SoftApConfiguration publicly (API 30+).
        // Prefer the AP interface IP — never the STA (wlan0) IP, which clients can't reach.
        val ip = getApIp() ?: getHostIp()
        return try {
            val cfg = res.softApConfiguration
            val ssid = (if (android.os.Build.VERSION.SDK_INT >= 33) cfg.wifiSsid?.toString() else cfg.ssid)
                .orEmpty().removeSurrounding("\"")
            val pass = cfg.passphrase ?: ""
            ApInfo(ssid, pass, ip, bandLabel = apBandLabel(cfg), staLabel = "")
        } catch (_: Exception) {
            ApInfo("", "", ip)
        }
    }

    /**
     * STRICT variant: ap0 interface IP only. Returns null (instead of a bogus
     * STA IP) when ap0 has no IPv4 yet — caller falls through to
     * systemApFallback() / manual-hotspot UI rather than printing a QR no
     * client can reach. This kills the stale-IP "tunnel exited" class.
     */
    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun extractStrict(res: WifiManager.LocalOnlyHotspotReservation): ApInfo? {
        val ip = getApIp() ?: return null
        return try {
            val cfg = res.softApConfiguration
            val ssid = (if (android.os.Build.VERSION.SDK_INT >= 33) cfg.wifiSsid?.toString() else cfg.ssid)
                .orEmpty().removeSurrounding("\"")
            val pass = cfg.passphrase ?: ""
            ApInfo(ssid, pass, ip, bandLabel = apBandLabel(cfg), staLabel = "")
        } catch (_: Exception) {
            ApInfo("", "", ip)
        }
    }

    /** Best-effort AP band label from SoftApConfiguration (API 30+).
     * Prefers the REAL channel when the framework reports one; otherwise falls
     * back to the requested band (OEMs like Samsung report the default/requested
     * config even when the running AP is on a different band). */
    private fun apBandLabel(cfg: Any?): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT < 30 || cfg == null) return fallbackBandLabel()
            val channel = try {
                (cfg.javaClass.getMethod("getChannel").invoke(cfg) as? Int) ?: 0
            } catch (_: Exception) { 0 }
            when {
                channel in 1..14 -> "2.4 GHz ch$channel"
                channel >= 32 -> "5 GHz ch$channel"
                else -> fallbackBandLabel()
            }
        } catch (_: Exception) { fallbackBandLabel() }
    }

    private fun fallbackBandLabel(): String = when (requestedBand) {
        ApBand.GHZ_2 -> "2.4 GHz (requested)"
        ApBand.GHZ_5 -> "5 GHz (requested)"
        ApBand.AUTO -> "auto"
    }

    fun stop() {
        try { reservation.getAndSet(null)?.close() } catch (_: Exception) {}
    }

    fun isUp(): Boolean = reservation.get() != null

    /** Current AP info if a hotspot is up. Lets the host UI restore its QR card
     * after an Activity/process recreation while HostService keeps running. */
    fun currentApInfo(): ApInfo? {
        val res = reservation.get()
        if (res != null) {
            val info = try { extract(res) } catch (_: Exception) { null }
            if (info != null && (info.ssid.isNotBlank() || info.ip.isNotBlank())) return info
        }
        return systemApFallback()
    }

    /** IPv4 of the actual AP interface (LOHS or system tether), if present. */
    fun getApIp(): String? {
        return try {
            val ifs = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name
                if (!(n.equals("ap0", true) || n.contains("softap", true) ||
                            n.contains("swlan", true) || n == "wlan1")) continue
                for (addr in ni.inetAddresses) {
                    if (addr.address.size != 4) continue
                    val ip = addr.hostAddress ?: continue
                    if (!ip.startsWith("127.")) return ip
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /** System (tethered) hotspot already ON? e.g. TECNO ap0 10.128.181.87 while LOHS fails. */
    fun isSystemApUp(): Boolean {
        return try {
            val ifs = NetworkInterface.getNetworkInterfaces()?.toList() ?: return false
            ifs.any { ni ->
                try {
                    ni.isUp && !ni.isLoopback &&
                        (ni.name.equals("ap0", true) || ni.name.contains("softap", true) || ni.name.contains("swlan", true)) &&
                        ni.inetAddresses.asSequence().any { it.address.size == 4 }
                } catch (_: Exception) { false }
            }
        } catch (_: Exception) { false }
    }

    /** QR fallback when auto-LOHS fails but system hotspot is already sharing. */
    fun systemApFallback(): ApInfo? {
        if (!isSystemApUp()) return null
        val ip = getHostIp()
        return if (ip.isNotBlank()) ApInfo("", "", ip) else null
    }

    /** Best-effort local hotspot gateway IP (softap interface first). */
    fun getHostIp(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            // Prefer obvious softap interface names, then any private IPv4.
            val ips = mutableListOf<Pair<String, Int>>() // ip, priority
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    val ip = addr.hostAddress ?: continue
                    if (addr.address.size != 4) continue
                    if (ip.startsWith("127.")) continue
                    val pri = when {
                        ni.name.contains("ap", true) || ni.name.contains("swlan", true) -> 0
                        ip.startsWith("192.168.43.") || ip.startsWith("192.168.44.") -> 1
                        ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.128.") -> 2
                        // Client STA subnets are LAST: picking the phone's own
                        // home-WiFi/cell IP into a QR guarantees an unreachable
                        // host. Prefer ap0; anything else is a last resort.
                        ip.startsWith("10.") || ip.startsWith("192.168.") -> 8
                        else -> 9
                    }
                    // Skip device's own cell data address heuristically? Keep list; caller picks first.
                    ips.add(ip to pri)
                }
            }
            ips.sortBy { it.second }
            ips.firstOrNull()?.let { return it.first }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }

    fun openHotspotSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Suppress("DEPRECATION")
    fun currentSsid(ctx: Context): String {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            (wm.connectionInfo?.ssid ?: "").replace("\"", "")
        } catch (_: Exception) { "" }
    }
}
