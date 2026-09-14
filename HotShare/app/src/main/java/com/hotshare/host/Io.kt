package com.hotshare.host

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

object Io {
    /**
     * Crash-safe relay: SocketException mid-copy is NORMAL (peer closed /
     * racing shutdown) and must NEVER propagate. An uncaught throw on a
     * worker thread kills the whole HostService process
     * (seen 16:49 FATAL Thread-21 HttpRelay.kt:50).
     * No shutdownInput() here: it races with the peer thread's blocked
     * getInputStream()/read() and throws. Socket close belongs to the owner.
     *
     * @param counter optional byte counter (host stats) incremented per chunk.
     */
    fun copy(inp: InputStream, out: OutputStream, counter: AtomicLong? = null) {
        try {
            val b = ByteArray(8192)
            while (true) {
                val r = try { inp.read(b) } catch (_: Exception) { break }
                if (r == -1) break
                try { out.write(b, 0, r); out.flush() } catch (_: Exception) { break }
                if (counter != null) counter.addAndGet(r.toLong())
            }
        } catch (_: Throwable) {
            // Swallow: relay threads must NEVER throw (process death).
        }
    }
}
