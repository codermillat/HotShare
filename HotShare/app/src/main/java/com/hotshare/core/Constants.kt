package com.hotshare.core

object Constants {
    const val HTTP_PORT = 8080
    const val SOCKS_PORT = 1080
    const val DNS_PRIMARY = "8.8.8.8"
    const val DNS_SECONDARY = "8.8.4.4"
    // Common Android hotspot gateways
    val GATEWAY_CANDIDATES = listOf("192.168.43.1", "192.168.44.1", "192.168.137.1", "192.168.49.1")
    const val JOIN_SCHEME = "hotshare"
    const val PREFS = "hotshare_prefs"
    const val KEY_ROLE = "role"
    const val KEY_AP_MODE = "ap_mode"
    const val KEY_SYS_SSID = "sys_ssid"
    const val KEY_SYS_PASS = "sys_pass"
    // Proxy auth: SOCKS5 username (password == JoinInfo.token). Empty token = open (subnet-restricted).
    const val AUTH_USER = "hotshare"
    // TUN MTU: 1500 = standard Ethernet, avoids fragmentation on hotspot path.
    const val TUN_MTU = 1500
}
