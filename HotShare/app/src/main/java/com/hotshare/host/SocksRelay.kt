package com.hotshare.host

import com.hotshare.core.Constants
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** SOCKS5 CONNECT (TCP) + UDP ASSOCIATE. IPv4/IPv6 + domain. Optional user/pass auth.
 * The ServerSocket is owned by HostService; callers use [handleSingle] per accepted socket. */
class SocksRelay(
    private val running: AtomicBoolean,
    private val authTokenProvider: () -> String = { "" }
) {
    /** Entry point when the ServerSocket is owned by HostService (clean restart).
     * @param requireAuth when true, the client MUST authenticate with the token. */
    fun handleSingle(
        client: Socket,
        expectedToken: String = authTokenProvider(),
        requireAuth: Boolean = false
    ) = handle(client, expectedToken, requireAuth)

    private fun handle(client: Socket, expectedToken: String = authTokenProvider(), requireAuth: Boolean = false) {
        try {
            client.tcpNoDelay = true
            val inp = client.getInputStream(); val out = client.getOutputStream()
            if (readByte(inp) != 5) { client.close(); return }
            val n = readByte(inp)
            val methods = ByteArray(n); readFully(inp, methods)
            val needAuth = expectedToken.isNotBlank()
            val hasNoAuth = methods.any { it == 0.toByte() }
            val hasUserPass = methods.any { it == 2.toByte() }
            when {
                // Strict: token required → client MUST present user/pass.
                needAuth && requireAuth -> {
                    if (!hasUserPass) { out.write(byteArrayOf(5, 0xFF.toByte())); out.flush(); client.close(); return }
                    out.write(byteArrayOf(5, 2)); out.flush()
                    if (!negotiateUserPass(inp, out, expectedToken, true)) { try { client.close() } catch (_: Exception) {} ; return }
                }
                // Non-strict: prefer no-auth so subnet clients without the token
                // (macOS/manual proxy, stale QR) still work — the hotspot WPA2
                // password + AP-IP bind is the real gate.
                hasNoAuth -> { out.write(byteArrayOf(5, 0)); out.flush() }
                // Client only speaks user/pass → complete it; validate only if we
                // actually have a token (open server accepts any).
                hasUserPass -> {
                    out.write(byteArrayOf(5, 2)); out.flush()
                    if (!negotiateUserPass(inp, out, expectedToken, needAuth)) { try { client.close() } catch (_: Exception) {} ; return }
                }
                else -> { out.write(byteArrayOf(5, 0xFF.toByte())); out.flush(); client.close(); return }
            }
            if (readByte(inp) != 5) { client.close(); return }
            when (readByte(inp)) {
                1 -> handleConnect(client, inp, out)
                3 -> handleUdpAssociate(client, inp, out)
                else -> client.close()
            }
        } catch (_: Exception) { try { client.close() } catch (_: Exception) {} }
    }

    /** RFC 1929 username/password subnegotiation. Returns true when accepted.
     * @param validate when false (open server) any password is accepted. */
    private fun negotiateUserPass(inp: InputStream, out: OutputStream, expectedToken: String, validate: Boolean): Boolean {
        return try {
            if (readByte(inp) != 1) { out.write(byteArrayOf(1, 1)); out.flush(); return false }
            val ulen = readByte(inp)
            readFully(inp, ByteArray(ulen))
            val plen = readByte(inp)
            val pb = ByteArray(plen); readFully(inp, pb)
            val ok = !validate || String(pb) == expectedToken
            out.write(byteArrayOf(1, if (ok) 0 else 1)); out.flush()
            ok
        } catch (_: Exception) { false }
    }

    private fun handleConnect(client: Socket, inp: InputStream, out: OutputStream) {
        readByte(inp) // rsv
        val host = readAddress(inp) ?: run { client.close(); return }
        val port = readPort(inp)
        val remote = Socket()
        try {
            remote.tcpNoDelay = true
            // Resolve + connect on the HOST device: client DNS queries egress as host too.
            remote.connect(InetSocketAddress(InetAddress.getByName(host), port), 15000)
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
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
            // No timeout: Io.copy ends on EOF/error.
            t1.join(); t2.join()
        } catch (_: Exception) {
            try { out.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush() } catch (_: Exception) {}
        } finally {
            try { remote.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** CMD 3: one UDP socket per client; client UDP addr learned from first datagram. */
    private fun handleUdpAssociate(client: Socket, inp: InputStream, out: OutputStream) {
        readByte(inp) // rsv
        readAddress(inp) // DST ignored per RFC (hev sends target)
        readPort(inp)
        val relay = DatagramSocket() // 0.0.0.0:<ephemeral>
        val bnd = byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0,
            ((relay.localPort shr 8) and 0xFF).toByte(), (relay.localPort and 0xFF).toByte())
        out.write(bnd); out.flush()

        // NOTE: the client's UDP source port NEVER equals its TCP port, so the
        // old check (from.port == TCP port) misclassified the first client
        // datagram as an internet reply and UDP never worked. Learn instead:
        // the first datagram after ASSOCIATE is always the client (internet
        // doesn't know our ephemeral port yet). AtomicReference = visible to
        // both the relay thread and the control-connection watcher.
        val clientUdp = java.util.concurrent.atomic.AtomicReference<InetSocketAddress?>(null)
        val closed = AtomicBoolean(false)
        val buf = ByteArray(65535)
        // Relay loop: datagrams from client (SOCKS5-UDP header) -> dst; others -> back to client.
        val relayThread = Thread {
            try {
                while (!closed.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    relay.receive(p)
                    val from = InetSocketAddress(p.address, p.port)
                    val data = p.data.copyOfRange(p.offset, p.offset + p.length)
                    val known = clientUdp.get()
                    val isClient = when {
                        known != null && from.address == known.address && from.port == known.port -> true
                        known == null -> true // first datagram = client, learn it
                        else -> false
                    }
                    if (isClient) {
                        clientUdp.set(from) // learn / refresh NAT mapping
                        if (data.size < 10 || u(data[2]) != 0 /* FRAG unsupported */) continue
                        var di = 4
                        val dstHost: String
                        when (u(data[3])) {
                            1 -> { dstHost = "${u(data[di])}.${u(data[di+1])}.${u(data[di+2])}.${u(data[di+3])}"; di += 4 }
                            3 -> { val l = u(data[di]); di += 1; dstHost = String(data, di, l); di += l }
                            4 -> { // IPv6
                                val sb = StringBuilder()
                                for (i in 0 until 16 step 2) {
                                    if (i > 0) sb.append(':')
                                    sb.append(String.format("%x", (u(data[di + i]) shl 8) or u(data[di + i + 1])))
                                }
                                dstHost = sb.toString(); di += 16
                            }
                            else -> continue
                        }
                        val dstPort = (u(data[di]) shl 8) or u(data[di + 1]); di += 2
                        if (di >= data.size) continue
                        val payload = data.copyOfRange(di, data.size)
                        try {
                            val dst = InetAddress.getByName(dstHost)
                            relay.send(DatagramPacket(payload, payload.size, dst, dstPort))
                        } catch (_: Exception) {}
                    } else {
                        val ca = clientUdp.get() ?: continue
                        // Reply from internet — wrap in SOCKS5 UDP header for client.
                        val src = from.address.address
                        val head: ByteArray = if (src.size == 4) byteArrayOf(0, 0, 0, 1) + src
                            else byteArrayOf(0, 0, 0, 4) + src
                        val wrapped = head +
                            byteArrayOf((from.port shr 8).toByte(), (from.port and 0xFF).toByte()) + data
                        try { relay.send(DatagramPacket(wrapped, wrapped.size, ca)) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {} finally {
                try { relay.close() } catch (_: Exception) {}
            }
        }
        relayThread.isDaemon = true
        relayThread.start()
        // Watch the control connection; when it closes, tear the relay down.
        Thread {
            try { while (inp.read() != -1) {} } catch (_: Exception) {}
            closed.set(true)
            try { relay.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    /** @return "1.2.3.4", IPv6 literal, or hostname, or null on bad ATYP. */
    private fun readAddress(inp: InputStream): String? = when (readByte(inp)) {
        1 -> { val b = ByteArray(4); readFully(inp, b); b.joinToString(".") { (it.toInt() and 0xFF).toString() } }
        3 -> { val l = readByte(inp); val b = ByteArray(l); readFully(inp, b); String(b) }
        // Unsigned bytes: mask &0xFF before formatting (signed Byte gave ffffffc0...).
        4 -> { val b = ByteArray(16); readFully(inp, b); try { InetAddress.getByAddress(b).hostAddress } catch (_: Exception) { null } }
        else -> null
    }

    private fun readPort(inp: InputStream): Int = (readByte(inp) shl 8) or readByte(inp)

    private fun readByte(inp: InputStream): Int {
        val r = inp.read(); if (r == -1) throw EOFException(); return r
    }

    private fun readFully(inp: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val r = inp.read(b, off, b.size - off)
            if (r == -1) throw EOFException()
            off += r
        }
    }
}

