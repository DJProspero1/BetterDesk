package app.betterdesk.core

/** Local counters only. No pixels, addresses or typed data, and no telemetry upload. */
class FramePerformance {
    private var started = System.nanoTime()
    private var received = 0L
    private var presented = 0L
    private var lastPresented = 0L
    private var lastDrawAt = 0L
    private var bytes = 0L
    private var decodeNanos = 0L
    private var queueDrops = 0L
    private var windowDrops = 0L
    private var recoveryDrops = 0L
    private val gaps = LongArray(1001)
    @Synchronized fun reset() {
        started = System.nanoTime(); received = 0; presented = 0; lastPresented = 0
        lastDrawAt = 0; bytes = 0; decodeNanos = 0; gaps.fill(0)
        queueDrops = 0; windowDrops = 0; recoveryDrops = 0
    }
    @Synchronized fun dropped(reason: String) { when(reason) { "queue" -> queueDrops++; "window" -> windowDrops++; "recovery" -> recoveryDrops++ } }
    @Synchronized fun received(encodedBytes: Int, nanos: Long) { received++; bytes += encodedBytes; decodeNanos += nanos }
    @Synchronized fun presented(id: Long) {
        if (id <= lastPresented) return
        val now = System.nanoTime()
        if (lastDrawAt > 0) gaps[((now - lastDrawAt) / 1_000_000).toInt().coerceIn(0, 1000)]++
        lastDrawAt = now; lastPresented = id; presented++
    }
    @Synchronized fun snapshot(): Snapshot {
        val seconds = (System.nanoTime() - started) / 1e9
        val target = kotlin.math.ceil((presented - 1).coerceAtLeast(0) * .95).toLong()
        var count = 0L
        val p95 = gaps.indexOfFirst { count += it; count >= target }
        return Snapshot(received, presented, seconds, received / seconds, presented / seconds, p95, bytes * 8 / seconds / 1e6, decodeNanos / received.coerceAtLeast(1) / 1e6, queueDrops, windowDrops, recoveryDrops)
    }
    data class Snapshot(val received: Long, val presented: Long, val seconds: Double, val receivedFps: Double,
                        val presentedFps: Double, val p95DrawGapMs: Int, val encodedMbps: Double, val decodeMs: Double,
                        val queueDrops: Long, val windowDrops: Long, val recoveryDrops: Long)
}
