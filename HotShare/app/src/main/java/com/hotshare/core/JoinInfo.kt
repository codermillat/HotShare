package com.hotshare.core

import android.net.Uri

data class JoinInfo(
    val ip: String,
    val httpPort: Int = Constants.HTTP_PORT,
    val socksPort: Int = Constants.SOCKS_PORT,
    val ssid: String = "",
    val pass: String = "",
    val token: String = "",
    val auto: Int = 0,
    val apBand: String = ""
) {
    fun toUri(): String =
        "hotshare://join?ip=$ip&http=$httpPort&socks=$socksPort&ssid=${Uri.encode(ssid)}&pass=${Uri.encode(pass)}&token=${Uri.encode(token)}&auto=$auto&band=${Uri.encode(apBand)}"

    companion object {
        fun fromUri(s: String): JoinInfo? {
            return try {
                val u = Uri.parse(s)
                val ip = u.getQueryParameter("ip") ?: return null
                JoinInfo(
                    ip = ip,
                    httpPort = u.getQueryParameter("http")?.toIntOrNull() ?: 8080,
                    socksPort = u.getQueryParameter("socks")?.toIntOrNull() ?: 1080,
                    ssid = u.getQueryParameter("ssid") ?: "",
                    pass = u.getQueryParameter("pass") ?: "",
                    token = u.getQueryParameter("token") ?: "",
                    auto = u.getQueryParameter("auto")?.toIntOrNull() ?: 0,
                    apBand = u.getQueryParameter("band") ?: ""
                )
            } catch (_: Exception) { null }
        }
    }
}
