package com.hotshare.host

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** HTTP proxy: CONNECT tunnel + plain-GET relay.
 * @param expectedToken non-blank enables token checks.
 * @param requireAuth when true, missing/invalid token is rejected (407).
 *   When false, a *present* token must still be valid, but absent is allowed
 *   (so macOS/manual proxy works; the AP-IP bind + subnet allowlist gate access). */
object HttpRelay {
    fun handle(client: Socket, expectedToken: String = "", requireAuth: Boolean = false) {
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val head = readHead(input) ?: run { client.close(); return }
            if (expectedToken.isNotBlank()) {
                val hasHeader = hasProxyAuth(head)
                val ok = hasHeader && checkAuth(head, expectedToken)
                if (requireAuth || hasHeader) {
                    if (!ok) {
                        try {
                            output.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"HotShare\"\r\n\r\n".toByteArray())
                            output.flush()
                        } catch (_: Exception) {}
                        try { client.close() } catch (_: Exception) {}
                        return
                    }
                }
            }
            val line = head.lines().firstOrNull() ?: run { client.close(); return }
            val parts = line.split(" ")
            if (parts.size < 2) { client.close(); return }
            val method = parts[0].uppercase()
            val target = parts[1]
            if (method == "CONNECT") {
                val host = target.substringBefore(":")
                val port = target.substringAfter(":", "443").toIntOrNull() ?: 443
                tunnel(client, output, host, port, null)
            } else {
                var host = ""; var port = 80
                val hostH = head.lines().drop(1).firstOrNull { it.startsWith("Host:", true) }
                    ?.substringAfter(":")?.trim()
                if (target.startsWith("http://")) {
                    val no = target.removePrefix("http://")
                    val auth = no.substringBefore("/")
                    host = auth.substringBefore(":")
                    port = auth.substringAfter(":", "80").toIntOrNull() ?: 80
                } else if (hostH != null) {
                    host = hostH.substringBefore(":")
                    port = hostH.substringAfter(":", "80").toIntOrNull() ?: 80
                } else { client.close(); return }
                tunnel(client, output, host, port, head.toByteArray())
            }
        } catch (_: Exception) { try { client.close() } catch (_: Exception) {} }
    }

    private fun hasProxyAuth(head: String): Boolean =
        head.lines().drop(1).any { it.startsWith("Proxy-Authorization:", true) }

    /** Accepts `Proxy-Authorization: Basic base64(user:token)` or `Bearer <token>`. */
    private fun checkAuth(head: String, expectedToken: String): Boolean {
        return try {
            val line = head.lines().drop(1).firstOrNull { it.startsWith("Proxy-Authorization:", true) }
                ?: return false
            val value = line.substringAfter(":").trim()
            if (value.startsWith("Bearer ", true)) {
                return value.substringAfter(" ").trim() == expectedToken
            }
            if (value.startsWith("Basic ", true)) {
                val decoded = try {
                    String(android.util.Base64.decode(value.substringAfter(" ").trim(), android.util.Base64.DEFAULT)).trim()
                } catch (_: Exception) { return false }
                // user:pass — pass must equal token (user is ignored, typically "hotshare").
                val pass = decoded.substringAfter(":", missingDelimiterValue = "")
                return pass == expectedToken || decoded == expectedToken
            }
            false
        } catch (_: Exception) { false }
    }

    private fun tunnel(client: Socket, out: OutputStream, host: String, port: Int, first: ByteArray?) {
        val remote = Socket()
        try {
            remote.connect(InetSocketAddress(host, port), 15000)
            if (first != null) { remote.getOutputStream().write(first) }
            else { out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); out.flush() }
            // Acquire streams BEFORE spawning: getInputStream() throws when the
            // peer already shut down — that uncaught throw killed the host 16:49.
            val cIn = try { client.getInputStream() } catch (_: Exception) { return }
            val rOut = try { remote.getOutputStream() } catch (_: Exception) { return }
            val rIn = try { remote.getInputStream() } catch (_: Exception) { return }
            val t1 = Thread { Io.copy(cIn, rOut, HostStats.clientToHost) }
            val t2 = Thread { Io.copy(rIn, out, HostStats.hostToClient) }
            for (t in arrayOf(t1, t2)) {
                t.isDaemon = true
                t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
                t.start()
            }
            // No timeout: long downloads/streams must survive past 60s.
            // Io.copy returns on EOF/error, so joins always terminate.
            t1.join(); t2.join()
        } catch (_: Exception) {
            try { out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()) } catch (_: Exception) {}
        } finally {
            try { remote.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var n = 0
        while (n < 65536) {
            val d = try { input.read() } catch (_: Exception) { -1 }
            if (d == -1) break
            sb.append(d.toChar()); n++
            if (sb.length >= 4 && sb.substring(sb.length - 4) == "\r\n\r\n") break
        }
        return if (sb.isEmpty()) null else sb.toString()
    }
}
