package com.hotshare.client

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hotshare.core.HotShareTheme
import com.hotshare.core.JoinInfo
import com.hotshare.ui.BrandTitle
import com.hotshare.ui.LicenseFooter
import com.hotshare.ui.MetricTile
import com.hotshare.ui.Sparkline
import com.hotshare.ui.StatusPill
import com.hotshare.ui.formatBytes
import com.hotshare.ui.formatDuration
import com.hotshare.ui.formatSpeed

class JoinActivity : ComponentActivity() {

    private var join by mutableStateOf(JoinInfo(ip = ""))
    private var status by mutableStateOf("Scan the host's QR, then Connect.")
    private var proxyWarn by mutableStateOf("")

    /** System global proxy (adb `settings put global http_proxy` or Wi-Fi manual proxy).
     * A normal app can only READ it — clearing needs adb or Wi-Fi settings. */
    private fun currentGlobalProxy(): String {
        return try {
            android.provider.Settings.Global.getString(contentResolver, "http_proxy").orEmpty()
        } catch (_: Exception) { "" }
    }

    /** Warn when a stale proxy points at an unreachable host while VPN is off —
     * every other network looks dead ("no internet") although Wi-Fi is fine. */
    private fun refreshProxyWarn() {
        if (ShareVpnService.isRunning) { proxyWarn = ""; return }
        val p = currentGlobalProxy()
        if (p.isBlank() || p == ":0") { proxyWarn = ""; return }
        proxyWarn = "Stale system proxy $p is ON — all apps are forced through the old host, " +
            "so other networks look dead. Clear it: Wi-Fi → modify network → Proxy None, " +
            "or adb: settings put global http_proxy :0"
    }

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        android.util.Log.i("HotShare", "vpn consent result code=${res.resultCode} data=${res.data}")
        val j = vpnPendingJoin
        vpnPendingJoin = null
        if (j == null) {
            status = "VPN dialog returned with no pending target — tap Connect again."
            return@registerForActivityResult
        }
        if (res.resultCode == Activity.RESULT_OK) {
            vpnApproved = true
            actuallyConnect(j)
        } else {
            status = "VPN permission denied (code ${res.resultCode}) — tunnel not started. " +
                "Proton works, so this is our consent Intent, not Family Link. Tap Connect to try again."
        }
    }

    private var vpnPendingJoin: JoinInfo? = null

    companion object {
        /** VPN approval is one-time per app: once prepare() returns null, never gate again. */
        @Volatile var vpnApproved: Boolean = false
    }

    /** Single consent check: null = already granted (remember it), intent = first-run approval. */
    private fun ensureVpnConsent(j: JoinInfo, onGranted: () -> Unit) {
        if (vpnApproved) { onGranted(); return }
        val prep: Intent? = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            android.util.Log.e("HotShare", "VpnService.prepare() threw", e)
            status = "VPN prepare failed: ${e.message}. Tap Connect to try again."
            return
        }
        android.util.Log.i("HotShare", "VpnService.prepare() -> ${if (prep == null) "null (consent already granted)" else "intent"}")
        if (prep == null) {
            vpnApproved = true
            onGranted()
            return
        }
        // First run only: park target, open system dialog. Callback continues.
        vpnPendingJoin = j
        status = "First-time setup: approve the VPN request…"
        try {
            android.util.Log.i("HotShare", "consent intent resolves to ${prep.resolveActivity(packageManager)}")
            vpnLauncher.launch(prep)
        } catch (e: Exception) {
            android.util.Log.e("HotShare", "consent launch failed", e)
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(prep, 1001)
            } catch (e2: Exception) {
                vpnPendingJoin = null
                status = "Could not open VPN approval: ${e2.message}. " +
                    "Open Settings → VPN → HotShare manually once."
            }
        }
        Thread {
            try { Thread.sleep(15000) } catch (_: Exception) {}
            if (vpnPendingJoin != null) {
                runOnUiThread {
                    if (vpnPendingJoin != null) {
                        vpnPendingJoin = null
                        status = "No VPN dialog appeared (system suppressed it). " +
                            "Grant once via Settings → Network → VPN → HotShare → allow, then Connect."
                    }
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun cm(): ConnectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun currentWifiSsid(): String {
        return try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            (wm.connectionInfo?.ssid ?: "").replace("\"", "")
        } catch (_: Exception) { "" }
    }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val qr = res.data?.getStringExtra("qr").orEmpty()
            val parsed = JoinInfo.fromUri(qr)
            if (parsed != null) { join = parsed; status = "Parsed. Tap Connect."; refreshProxyWarn() }
            else status = "Scanned but not a HotShare QR."
        }
    }

    private val locLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (fine) connect() else status = "Location permission required to join the hotspot."
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification is required so the VPN status shows; connect proceeds regardless */ }

    private fun requestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) {}
        }
    }

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onResume() {
        super.onResume()
        // Tunnel died outside our button (system revoke, hotspot loss, crash,
        // or preflight/establish failure right after Connect)? Surface it so
        // the user knows the phone is free, not stuck on ghost "Connected".
        // Also confirm a real tunnel: flip interim "Connecting" -> "Connected".
        if (ShareVpnService.isRunning) {
            if (status.startsWith("Connecting")) {
                status = status.replace("Connecting →", "Connected →")
                    .replace("…", ". All apps now ride the host. Test: open any website.")
            }
        } else if (status.startsWith("Connected") || status.startsWith("Connecting") ||
            status.startsWith("Disconnecting")) {
            val r = ShareVpnService.lastStopReason
            status = if (r.isNotBlank() && r != "disconnected") "Disconnected ($r). Pick any network." else "Disconnected. Pick any network."
            if (r == "disconnected") ShareVpnService.lastStopReason = ""
        }
        refreshProxyWarn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Accept hotshare://join?... launched from scanner/share. auto=1 → run connect() immediately.
        var autoRun = false
        intent?.dataString?.let { s ->
            android.util.Log.d("HotShare", "JOIN uri=$s")
            JoinInfo.fromUri(s)?.let { j ->
                join = j; autoRun = j.auto == 1
                android.util.Log.d("HotShare", "JOIN parsed ip=${j.ip} ssid=${j.ssid} auto=${j.auto} socks=${j.socksPort}")
            }
        }
        setContent { JoinUi() }
        if (autoRun) {
            android.util.Log.d("HotShare", "JOIN auto-connecting")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ connect() }, 600)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun JoinUi() {
        HotShareTheme {
            val running = ShareVpnService.isRunning
            var showForm by remember { mutableStateOf(false) }
            val downHist = remember { mutableStateListOf<Float>() }
            // Poll monitor state once per second for a live speed meter.
            LaunchedEffect(running) {
                while (true) {
                    if (running) {
                        downHist.add(ShareVpnService.downBps.toFloat())
                        if (downHist.size > 60) downHist.removeAt(0)
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
            val connected = running
            val connecting = !running && status.startsWith("Connecting")
            val pillColor = when {
                connected -> Color(0xFF22C55E)
                connecting -> Color(0xFFF59E0B)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Scaffold(topBar = { TopAppBar(title = { BrandTitle("HotShare — Client") }) }) { pad ->
                Column(
                    Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(
                                    when {
                                        connected -> "Connected"
                                        connecting -> "Connecting"
                                        else -> "Disconnected"
                                    }, pillColor
                                )
                                Spacer(Modifier.weight(1f))
                                if (connected) {
                                    Text(
                                        formatDuration(ShareVpnService.uptimeMs()),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (connected) {
                                Text("Host ${ShareVpnService.connectedHost}", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MetricTile("Download", formatSpeed(ShareVpnService.downBps),
                                        MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                                    MetricTile("Upload", formatSpeed(ShareVpnService.upBps),
                                        MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MetricTile("Total ↓", formatBytes(ShareVpnService.downTotal),
                                        MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                                    MetricTile("Total ↑", formatBytes(ShareVpnService.upTotal),
                                        MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                                }
                                Sparkline(downHist.toList(), MaterialTheme.colorScheme.primary,
                                    Modifier.fillMaxWidth().height(56.dp))
                            } else {
                                Text(status)
                            }
                            if (connected) {
                                Button(onClick = { disconnect() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Disconnect")
                                }
                            } else {
                                Button(
                                    onClick = { if (join.ip.isNotBlank()) connect() },
                                    enabled = join.ip.isNotBlank() && !connecting,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (connecting) "Connecting…" else "Connect — all traffic via host (VPN)") }
                            }
                        }
                    }

                    if (!connected && !connecting) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { scanLauncher.launch(Intent(this@JoinActivity, ScanActivity::class.java)) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Scan host QR") }
                                if (showForm) {
                                    OutlinedTextField(value = join.ip, onValueChange = { join = join.copy(ip = it) },
                                        label = { Text("Host IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = join.ssid, onValueChange = { join = join.copy(ssid = it) },
                                        label = { Text("Wi-Fi SSID (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = join.pass, onValueChange = { join = join.copy(pass = it) },
                                        label = { Text("Wi-Fi password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedTextField(value = join.httpPort.toString(),
                                            onValueChange = { join = join.copy(httpPort = it.toIntOrNull() ?: 8080) },
                                            label = { Text("HTTP") }, singleLine = true, modifier = Modifier.weight(1f))
                                        OutlinedTextField(value = join.socksPort.toString(),
                                            onValueChange = { join = join.copy(socksPort = it.toIntOrNull() ?: 1080) },
                                            label = { Text("SOCKS") }, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                } else {
                                    OutlinedButton(onClick = { showForm = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Enter details manually")
                                    }
                                }
                            }
                        }
                    }

                    if (proxyWarn.isNotBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(proxyWarn, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Not using VPN? Set Wi-Fi proxy ${join.ip.ifBlank { "<host-ip>" }}:${join.httpPort}, then back to None when you leave.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LicenseFooter()
                }
            }
        }
    }

    /** 1) join hotspot via specifier (plug-and-play), 2) pin process to it, 3) VPN consent, 4) start tun2socks. */
    private fun connect() {
        val j = join
        android.util.Log.d("HotShare", "JOIN connect(): ssid='${j.ssid}' ip=${j.ip}")
        requestNotifPermission()
        if (j.ip.isBlank()) { status = "Enter host IP (or scan QR)."; return }
        if (j.ssid.isBlank()) {
            // Already-connected path: verify we are on Wi-Fi, pin to the ACTIVE network.
            val cur = currentWifiSsid()
            if (cur.isBlank() || cur == "<unknown ssid>") {
                status = "Not on host Wi-Fi yet — join the hotspot Wi-Fi first, or fill SSID+password."
                return
            }
            pinActive { ensureVpnConsent(j) { actuallyConnect(j) } }
            return
        }
        val cur = currentWifiSsid()
        if (cur == j.ssid) {
            pinActive { ensureVpnConsent(j) { actuallyConnect(j) } }
            return
        }
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val nearby = if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        if (!fine || !nearby) {
            android.util.Log.d("HotShare", "JOIN requesting wifi join perms")
            val req = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (android.os.Build.VERSION.SDK_INT >= 33) req.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            locLauncher.launch(req.toTypedArray())
            return
        }
        android.util.Log.d("HotShare", "JOIN vpn-consent (once) then wifi join")
        ensureVpnConsent(j) { startVpn() }
    }

    /** Pin the live ACTIVE network (validated) then run the block. No consent involved. */
    private fun pinActive(then: () -> Unit) {
        status = "Starting VPN…"
        try {
            val active = cm().activeNetwork
            val valid = try { active != null && cm().getNetworkCapabilities(active) != null } catch (_: Exception) { false }
            ShareVpnService.pinnedNetwork = if (valid) active else cm().activeNetwork
            ShareVpnService.pinnedNetwork?.let { cm().bindProcessToNetwork(it) }
        } catch (_: Exception) {}
        then()
    }

    @Deprecated("Use ensureVpnConsent (one-time). Kept so no caller can double-ask.")
    private fun requestVpnConsent(onGranted: () -> Unit = {}) {
        ensureVpnConsent(join, onGranted)
    }

    private fun startVpn() {
        val j = join
        val cm = cm()
        if (j.ssid.isBlank() || currentWifiSsid() == j.ssid) {
            launchVpn(j, cm)
            return // already on the hotspot Wi-Fi
        }
        status = "Joining ${j.ssid}… approve the Wi-Fi dialog if one appears."
        android.util.Log.i("HotShare", "JOIN specifier request ssid=${j.ssid}")
        Thread {
            // Try WPA2 first, then WPA3 (SAE). Some hosts run WPA3-only or
            // WPA2/WPA3 transition mode — a single security type fails on those.
            val modes = if (j.pass.isBlank()) listOf(0)
            else buildList {
                add(1)
                if (android.os.Build.VERSION.SDK_INT >= 29) add(2)
            }
            var net: Network? = null
            for (mode in modes) {
                net = tryJoinNetwork(j, cm, mode)
                if (net != null) break
            }
            if (net == null) {
                runOnUiThread {
                    status = "Couldn't auto-join ${j.ssid}. Opening Wi-Fi settings — connect to it, then tap Connect."
                    // Single-radio phones can't hold two same-band Wi-Fi links, so
                    // auto-join can fail (e.g. 5 GHz client + 5 GHz hotspot). Let
                    // the user join in one tap; the "already connected" path then works.
                    try {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {}
                }
                return@Thread
            }
            ShareVpnService.pinnedNetwork = net
            runOnUiThread { launchVpn(j, cm) }
        }.also { it.isDaemon = true }.start()
    }

    /** One specifier attempt. [security] 0=open, 1=WPA2, 2=WPA3(SAE). Returns the network or null. */
    private fun tryJoinNetwork(j: JoinInfo, cm: ConnectivityManager, security: Int): Network? {
        val builder = WifiNetworkSpecifier.Builder().setSsid(j.ssid)
        try {
            when (security) {
                1 -> builder.setWpa2Passphrase(j.pass)
                2 -> if (android.os.Build.VERSION.SDK_INT >= 29) builder.setWpa3Passphrase(j.pass) else return null
                else -> { /* open network: no passphrase */ }
            }
        } catch (_: Exception) { return null }
        val specifier = try { builder.build() } catch (_: Exception) { return null }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                result = network
                ShareVpnService.pinnedNetwork = network
                android.util.Log.i("HotShare", "JOIN specifier onAvailable security=$security net=$network")
                latch.countDown()
            }
            override fun onUnavailable() {
                android.util.Log.w("HotShare", "JOIN specifier onUnavailable security=$security")
                latch.countDown()
            }
            override fun onLost(network: Network) {
                // Hotspot vanished mid-session → free the phone (only if tunneled).
                if (ShareVpnService.isRunning) onSpecifierLost(cm, network)
            }
        }
        netCallback = callback
        ShareVpnService.attachWifi(cm, callback)
        return try {
            android.util.Log.i("HotShare", "JOIN requestNetwork security=$security, wait 25s")
            cm.requestNetwork(request, callback)
            val ok = latch.await(25, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok || result == null) {
                runCatching { cm.unregisterNetworkCallback(callback) }
                ShareVpnService.releaseWifi()
                if (netCallback === callback) netCallback = null
                null
            } else result
        } catch (e: Exception) {
            android.util.Log.w("HotShare", "JOIN specifier threw security=$security: ${e.message}")
            runCatching { cm.unregisterNetworkCallback(callback) }
            ShareVpnService.releaseWifi()
            if (netCallback === callback) netCallback = null
            null
        }
    }

    private fun onSpecifierLost(cm: ConnectivityManager, network: Network) {
        runCatching {
            ShareVpnService.stop(applicationContext)
            runOnUiThread { status = "Hotspot lost — disconnecting…" }
            Thread {
                val deadline = System.currentTimeMillis() + 6000
                while (ShareVpnService.isRunning && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(150) } catch (_: Exception) { break }
                }
                runCatching { ShareVpnService.releaseWifi() }
                runCatching { cm.bindProcessToNetwork(null) }
                ShareVpnService.pinnedNetwork = null
                netCallback = null
                runOnUiThread {
                    status = "Hotspot lost — disconnected. Pick any network."
                    refreshProxyWarn()
                }
            }.also { it.isDaemon = true }.start()
        }
    }

    private fun launchVpn(j: JoinInfo, cm: ConnectivityManager) {
        // Consent was already ensured upstream (ensureVpnConsent, one-time).
        // NEVER re-gate here: start the tunnel immediately.
        android.util.Log.i("HotShare", "launchVpn: consent already ensured, starting tunnel")
        actuallyConnect(j)
    }

    /** Start the tunnel AFTER VPN consent is confirmed. Runs on UI thread. */
    private fun actuallyConnect(j: JoinInfo) {
        try {
            val cm = cm()
            val net = ShareVpnService.pinnedNetwork ?: cm.activeNetwork
            // Validate the network is still alive before pinning a stale handle.
            val valid = try {
                net != null && cm.getNetworkCapabilities(net) != null
            } catch (_: Exception) { false }
            ShareVpnService.pinnedNetwork = if (valid) net else cm.activeNetwork
            ShareVpnService.pinnedNetwork?.let { cm.bindProcessToNetwork(it) }
        } catch (_: Exception) {}
        ShareVpnService.lastStopReason = ""
        ShareVpnService.start(this, j.ip, j.socksPort, j.token)
        proxyWarn = ""
        // Honest interim state — onResume() corrects to Disconnected(reason) if
        // preflight/establish fails instead of leaving a ghost "Connected".
        // (VPN approval was one-time and already done — never mention it here.)
        status = if (j.token.isNotBlank()) "Connecting → ${j.ip}:${j.socksPort} (authed)…"
        else "Connecting → ${j.ip}:${j.socksPort}…"
    }

    private fun disconnect() {
        // Foolproof disconnect: DON'T unbind/unpin here — the tun still routes
        // 0.0.0.0/0 to the (now dead) SOCKS for ~1-2s. Unbinding first creates a
        // routing loop (tun -> default -> tun) = "net not working". Let the
        // SERVICE tear down tun first, then unbind/release as the last step.
        // We only release our LOCAL specifier callback handle here; the service
        // owns the global pin/bind and clears them in shutdownSync().
        if (!ShareVpnService.isRunning && ShareVpnService.lastStopReason.isBlank() &&
            ShareVpnService.pinnedNetwork == null) {
            status = "Disconnected. Pick any network."
            refreshProxyWarn()
            return
        }
        status = "Disconnecting… please wait."
        ShareVpnService.stop(this)
        // Release our local handle (service holds its own ref and also releases).
        try { netCallback?.let { cm().unregisterNetworkCallback(it) } } catch (_: Exception) {}
        netCallback = null
        // Wait for the tunnel to actually die before declaring Disconnected —
        // otherwise the UI lies ("Disconnected") while traffic still blackholes.
        Thread {
            val deadline = System.currentTimeMillis() + 6000
            while (ShareVpnService.isRunning && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(150) } catch (_: Exception) { break }
            }
            // Safety net: if the service somehow survived, force-stop + local unbind
            // so the phone is NEVER left blackholed.
            if (ShareVpnService.isRunning) {
                runCatching { stopService(Intent(this, ShareVpnService::class.java)) }
                try { Thread.sleep(800) } catch (_: Exception) {}
            }
            // Final safety: these are no-ops if the service already cleaned up.
            runCatching { ShareVpnService.releaseWifi() }
            runCatching { cm().bindProcessToNetwork(null) }
            ShareVpnService.pinnedNetwork = null
            runOnUiThread {
                val r = ShareVpnService.lastStopReason
                status = if (r.isNotBlank() && r != "disconnected" && r != "destroyed")
                    "Disconnected ($r). Pick any network."
                else "Disconnected. Pick any network."
                if (r == "disconnected" || r == "destroyed") ShareVpnService.lastStopReason = ""
                refreshProxyWarn()
            }
        }.also { it.isDaemon = true }.start()
    }

    @Deprecated("legacy consent fallback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            android.util.Log.i("HotShare", "legacy vpn consent result=$resultCode")
            val j = vpnPendingJoin
            vpnPendingJoin = null
            if (j != null && resultCode == Activity.RESULT_OK) { vpnApproved = true; actuallyConnect(j) }
            else if (j != null) status = "VPN permission denied (code $resultCode) — tap Connect to try again."
        }
    }

    override fun onDestroy() {
        try { netCallback?.let { cm().unregisterNetworkCallback(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
