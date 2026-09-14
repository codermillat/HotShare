package com.hotshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hotshare.client.JoinActivity
import com.hotshare.core.HotShareTheme
import com.hotshare.core.JoinInfo
import com.hotshare.host.HostService
import com.hotshare.host.HostStats
import com.hotshare.host.HotspotHelper
import com.hotshare.host.QrGenerator
import com.hotshare.ui.BrandTitle
import com.hotshare.ui.MetricTile
import com.hotshare.ui.Sparkline
import com.hotshare.ui.StatusPill
import com.hotshare.ui.formatBytes
import com.hotshare.ui.formatDuration
import com.hotshare.ui.LicenseFooter
import com.hotshare.ui.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Home() }
    }

    /** Per-session proxy token (12-char alphanum). Empty = open (legacy manual proxy). */
    private fun newToken(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        return (1..12).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private fun sharePerms(): Array<String> {
        val p = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        return p.toTypedArray()
    }

    private fun permsGranted(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val nearby = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        return fine && nearby
    }

    private fun startSharing(
        mode: HotspotHelper.ApMode,
        band: HotspotHelper.ApBand,
        token: String,
        requireAuth: Boolean,
        sysSsid: String,
        sysPass: String,
        onInfo: (HotspotHelper.ApInfo) -> Unit,
        onFail: (String) -> Unit
    ) {
        fun enrich(info: HotspotHelper.ApInfo) =
            info.copy(staLabel = HotspotHelper.staBandLabel(this))

        fun startWithSystemHotspot() {
            if (HotspotHelper.isSystemApUp()) {
                val ip = HotspotHelper.getApIp() ?: HotspotHelper.getHostIp()
                HostService.start(this, token, requireAuth)
                onInfo(HotspotHelper.ApInfo(sysSsid, sysPass, ip, bandLabel = "mobile"))
            } else {
                HotspotHelper.openHotspotSettings(this)
                onFail("Mobile hotspot is OFF. Enable it (set SSID + password), then tap Share again.")
            }
        }

        when (mode) {
            HotspotHelper.ApMode.TEMPORARY -> HotspotHelper.startWithBandFallback(this, band) { info ->
                if (info != null && (info.ssid.isNotBlank() || info.ip.isNotBlank())) {
                    HostService.start(this, token, requireAuth)
                    onInfo(enrich(info))
                } else {
                    onFail(
                        "Could not start the temporary hotspot (reason=${HotspotHelper.lastFailReason}). " +
                            "Choose Auto or 'Mobile hotspot' instead."
                    )
                }
            }

            HotspotHelper.ApMode.SYSTEM -> startWithSystemHotspot()

            HotspotHelper.ApMode.AUTO -> HotspotHelper.startWithBandFallback(this, band) { info ->
                if (info != null && (info.ssid.isNotBlank() || info.ip.isNotBlank())) {
                    HostService.start(this, token, requireAuth)
                    onInfo(enrich(info))
                } else {
                    // Auto-LOHS failed — use the system hotspot if it is already on.
                    val sys = HotspotHelper.systemApFallback()
                    if (sys != null) {
                        HostService.start(this, token, requireAuth)
                        onInfo(enrich(sys.copy(ssid = sysSsid, pass = sysPass)))
                    } else {
                        onFail(
                            "Auto-hotspot failed (reason=${HotspotHelper.lastFailReason}). " +
                                "Enable the Mobile hotspot manually, or pick a different Hotspot type, then tap Share again."
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Home() {
        var running by remember { mutableStateOf(HostService.isRunning) }
        var ap by remember { mutableStateOf<HotspotHelper.ApInfo?>(null) }
        var status by remember { mutableStateOf("") }
        var band by remember { mutableStateOf(HotspotHelper.ApBand.AUTO) }
        var token by remember { mutableStateOf("") }
        var requireAuth by remember { mutableStateOf(false) }
        var showAdvanced by remember { mutableStateOf(false) }
        val prefs = remember { getSharedPreferences(com.hotshare.core.Constants.PREFS, android.content.Context.MODE_PRIVATE) }
        var apMode by remember {
            mutableStateOf(
                runCatching {
                    HotspotHelper.ApMode.valueOf(
                        prefs.getString(com.hotshare.core.Constants.KEY_AP_MODE, HotspotHelper.ApMode.AUTO.name)!!
                    )
                }.getOrDefault(HotspotHelper.ApMode.AUTO)
            )
        }
        var sysSsid by remember { mutableStateOf(prefs.getString(com.hotshare.core.Constants.KEY_SYS_SSID, "") ?: "") }
        var sysPass by remember { mutableStateOf(prefs.getString(com.hotshare.core.Constants.KEY_SYS_PASS, "") ?: "") }
        var tick by remember { mutableStateOf(0) }
        val downHist = remember { mutableStateListOf<Float>() }
        val ctx = androidx.compose.ui.platform.LocalContext.current

        fun doShare() {
            status = "Starting hotspot…"
            if (token.isBlank()) token = newToken()
            prefs.edit()
                .putString(com.hotshare.core.Constants.KEY_AP_MODE, apMode.name)
                .putString(com.hotshare.core.Constants.KEY_SYS_SSID, sysSsid)
                .putString(com.hotshare.core.Constants.KEY_SYS_PASS, sysPass)
                .apply()
            startSharing(
                apMode, band, token, requireAuth, sysSsid, sysPass,
                onInfo = {
                    ap = it
                    running = true
                    status = if (it.ssid.isNotBlank()) "Sharing — show the QR to clients."
                    else "Sharing — open Advanced and enter your mobile hotspot SSID/password so the QR carries them."
                },
                onFail = { msg ->
                    status = msg
                    running = HostService.isRunning
                }
            )
        }

        fun copyText(label: String, value: String) {
            try {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                android.widget.Toast.makeText(ctx, "$label copied", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }

        val notifPerm = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* granted or not — sharing proceeds regardless */ }
        fun requestNotif() {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val locPerms = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            if (permsGranted()) { requestNotif(); doShare() }
            else status = "Location + Nearby-WiFi permission required for plug-and-play hotspot. Tap Share again."
        }

        // Restore QR/details card + token if the Activity was recreated while sharing.
        LaunchedEffect(running) {
            if (running && ap == null) {
                HotspotHelper.currentApInfo()?.let {
                    ap = it.copy(staLabel = HotspotHelper.staBandLabel(this@MainActivity))
                }
                if (token.isBlank()) token = HostService.activeToken
                requireAuth = HostService.activeRequireAuth
            }
        }
        // Live speed graph samples (speeds themselves are updated by HostService).
        LaunchedEffect(running) {
            while (true) {
                tick++
                if (running) {
                    downHist.add(HostStats.downBps.toFloat())
                    if (downHist.size > 60) downHist.removeAt(0)
                }
                kotlinx.coroutines.delay(1000)
            }
        }

        HotShareTheme {
            Scaffold(topBar = { TopAppBar(title = { BrandTitle("HotShare") }) }) { pad ->
                Column(
                    Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ---- Host status / monitor ----
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(
                                    if (running) "Sharing" else "Stopped",
                                    if (running) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                if (running && HostStats.startedAt > 0) {
                                    Text(
                                        formatDuration(System.currentTimeMillis() - HostStats.startedAt),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (running) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MetricTile("Clients", HostStats.activeClients().toString(),
                                        MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                                    MetricTile("Download", formatSpeed(HostStats.downBps),
                                        MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                                    MetricTile("Upload", formatSpeed(HostStats.upBps),
                                        MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MetricTile("Total ↓", formatBytes(HostStats.hostToClient.get()),
                                        MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                                    MetricTile("Total ↑", formatBytes(HostStats.clientToHost.get()),
                                        MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                                }
                                Sparkline(downHist.toList(), MaterialTheme.colorScheme.primary,
                                    Modifier.fillMaxWidth().height(56.dp))
                                Button(onClick = {
                                    HostService.stop(this@MainActivity)
                                    HotspotHelper.stop()
                                    running = false; ap = null; token = ""; downHist.clear()
                                    status = "Stopped. Turn off System Hotspot manually if you're done."
                                }, modifier = Modifier.fillMaxWidth()) { Text("Stop sharing") }
                            } else {
                                Text(
                                    "Share this phone's internet with your other devices. Client traffic is routed and authenticated through this phone.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(onClick = {
                                    status = "Checking permissions…"
                                    if (permsGranted()) { requestNotif(); doShare() } else locPerms.launch(sharePerms())
                                }, modifier = Modifier.fillMaxWidth()) { Text("Share internet (host)") }
                            }
                        }
                    }

                    // ---- QR / pairing card ----
                    ap?.let { j ->
                        var ssidEdit by remember(j.ip) { mutableStateOf(j.ssid) }
                        var passEdit by remember(j.ip) { mutableStateOf(j.pass) }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Pair a client", style = MaterialTheme.typography.titleMedium)
                                if (j.ssid.isBlank()) {
                                    OutlinedTextField(value = ssidEdit, onValueChange = { ssidEdit = it },
                                        label = { Text("System hotspot SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = passEdit, onValueChange = { passEdit = it },
                                        label = { Text("System hotspot password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        MetricTile("Wi-Fi", j.ssid, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                                        MetricTile("Password", j.pass, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MetricTile("Host", j.ip, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                                    MetricTile("AP band", j.bandLabel.ifBlank { band.label },
                                        MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                                }
                                if (j.staLabel.isNotBlank()) {
                                    Text("ISP link: ${j.staLabel}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = { copyText("Host", j.ip) }, modifier = Modifier.weight(1f)) { Text("Copy IP") }
                                    OutlinedButton(onClick = { copyText("Password", passEdit) }, modifier = Modifier.weight(1f)) { Text("Copy pass") }
                                }
                                val joinText = JoinInfo(
                                    ip = j.ip, httpPort = 8080, socksPort = 1080,
                                    ssid = ssidEdit, pass = passEdit, token = token,
                                    apBand = j.bandLabel.ifBlank { band.label }
                                ).toUri()
                                var qr by remember { mutableStateOf<Bitmap?>(null) }
                                var qrErr by remember { mutableStateOf("") }
                                LaunchedEffect(joinText) {
                                    qr = null; qrErr = ""
                                    qr = try {
                                        withContext(Dispatchers.Default) { QrGenerator.make(joinText, 480) }
                                    } catch (e: Exception) {
                                        qrErr = "QR failed: ${e.message} — clients can enter the IP manually."
                                        null
                                    }
                                }
                                qr?.let {
                                    Image(
                                        it.asImageBitmap(),
                                        contentDescription = "Pairing QR",
                                        modifier = Modifier.size(240.dp).align(Alignment.CenterHorizontally)
                                    )
                                }
                                if (qr == null && qrErr.isBlank()) {
                                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                                }
                                if (qrErr.isNotBlank()) Text(qrErr, style = MaterialTheme.typography.bodySmall)
                                Text("Client: HotShare → Scan QR (or enter IP) → Connect. Token is in the QR.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // ---- Advanced (band + security) ----
                    if (!running) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Advanced • ${apMode.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    OutlinedButton(onClick = { showAdvanced = !showAdvanced }) {
                                        Text(if (showAdvanced) "Hide" else "Show")
                                    }
                                }
                                if (showAdvanced) {
                                    Text("Hotspot type", style = MaterialTheme.typography.labelLarge)
                                    HotspotHelper.ApMode.values().forEach { m ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().clickable { apMode = m }
                                        ) {
                                            RadioButton(selected = apMode == m, onClick = null)
                                            Text(m.label, modifier = Modifier.padding(start = 4.dp))
                                        }
                                    }
                                    Text(
                                        when (apMode) {
                                            HotspotHelper.ApMode.AUTO ->
                                                "Tries the app's temporary hotspot first; uses your Mobile hotspot if that fails."
                                            HotspotHelper.ApMode.TEMPORARY ->
                                                "App creates a temporary hotspot (random name/password). No setup needed."
                                            HotspotHelper.ApMode.SYSTEM ->
                                                "Uses your phone's normal Mobile hotspot. Tip: set its band to 2.4 GHz — clients already on a 5 GHz Wi-Fi can't hold a second 5 GHz AP on single-radio phones."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    if (apMode != HotspotHelper.ApMode.SYSTEM) {
                                        Text("Hotspot band", style = MaterialTheme.typography.labelLarge)
                                        HotspotHelper.ApBand.values().forEach { b ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth().clickable { band = b }
                                            ) {
                                                RadioButton(selected = band == b, onClick = null)
                                                Text(b.label, modifier = Modifier.padding(start = 4.dp))
                                            }
                                        }
                                        Text(HotspotHelper.bandHint(this@MainActivity, band),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Text("Mobile hotspot details (so the QR carries them)",
                                            style = MaterialTheme.typography.labelLarge)
                                        OutlinedTextField(value = sysSsid, onValueChange = { sysSsid = it },
                                            label = { Text("Mobile hotspot SSID") }, singleLine = true,
                                            modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(value = sysPass, onValueChange = { sysPass = it },
                                            label = { Text("Mobile hotspot password") }, singleLine = true,
                                            modifier = Modifier.fillMaxWidth())
                                        OutlinedButton(onClick = { HotspotHelper.openHotspotSettings(ctx) },
                                            modifier = Modifier.fillMaxWidth()) { Text("Open hotspot settings") }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(checked = requireAuth, onCheckedChange = { requireAuth = it })
                                        Text("Require QR token", modifier = Modifier.padding(start = 12.dp))
                                    }
                                    Text(
                                        if (requireAuth) "Strict: only clients with the QR token may use the proxy."
                                        else "Default: any device on your hotspot may use it (WPA2 gate).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    OutlinedButton(onClick = {
                        startActivity(Intent(this@MainActivity, JoinActivity::class.java))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open client mode (join another host)")
                    }
                    if (status.isNotBlank()) {
                        Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    LicenseFooter()
                }
            }
        }
    }
}
