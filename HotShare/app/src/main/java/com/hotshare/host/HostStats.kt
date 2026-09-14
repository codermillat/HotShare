package com.hotshare.host

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Live host-side counters exposed to the UI + notification.
 *  - [clientToHost] bytes uploaded by clients (client -> internet)
 *  - [hostToClient] bytes downloaded to clients (internet -> client)
 *  Clients are keyed by IP with a last-seen timestamp so "connected" reflects
 *  real device presence, not short-lived HTTP sessions. */
object HostStats {
    val clientToHost = AtomicLong(0)
    val hostToClient = AtomicLong(0)

    /** When sharing started (for uptime). */
    @Volatile var startedAt: Long = 0L

    private val clients = ConcurrentHashMap<String, Long>()

    fun markClient(ip: String) {
        if (ip.isBlank()) return
        clients[ip] = System.currentTimeMillis()
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
    }

    /** Clients seen in the last 30 s (prunes stale entries). */
    fun activeClients(): Int {
        val now = System.currentTimeMillis()
        val it = clients.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > 30_000L) it.remove()
        }
        return clients.size
    }

    @Volatile private var lastSampleAt = 0L
    @Volatile private var lastUp = 0L
    @Volatile private var lastDown = 0L
    @Volatile var upBps = 0L; private set
    @Volatile var downBps = 0L; private set

    /** Recompute up/down speed since the previous call. Call ~1×/s. */
    fun sample() {
        val now = System.currentTimeMillis()
        val up = clientToHost.get()
        val down = hostToClient.get()
        if (lastSampleAt != 0L) {
            val dt = (now - lastSampleAt).coerceAtLeast(1L)
            upBps = ((up - lastUp) * 1000L) / dt
            downBps = ((down - lastDown) * 1000L) / dt
        }
        lastUp = up; lastDown = down; lastSampleAt = now
    }

    fun reset() {
        clientToHost.set(0); hostToClient.set(0)
        clients.clear(); startedAt = 0L
        lastSampleAt = 0L; lastUp = 0; lastDown = 0; upBps = 0; downBps = 0
    }
}
