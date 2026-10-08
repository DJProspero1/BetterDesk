package app.betterdesk.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Bounds encoded frames in flight, so a slow connection drops old capture instead of queuing video. */
class FrameWindow(private val capacity: Int = 2) {
    private val pending = linkedSetOf<Long>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    suspend fun awaitOffer(id: Long, timeoutMillis: Long = 16): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!offer(id)) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) return false
            // Reserve outside withTimeout: asynchronous timeout must never leak a slot.
            if (withTimeoutOrNull(remaining) { available.receive(); true } != true) return false
        }
        return true
    }
    @Synchronized fun offer(id: Long): Boolean {
        if (pending.size >= capacity || id <= 0) return false
        pending.add(id)
        return true
    }
    @Synchronized fun acknowledge(id: Long) { if (pending.remove(id)) available.trySend(Unit) }
    @Synchronized fun clear() { pending.clear(); available.trySend(Unit) }
}
