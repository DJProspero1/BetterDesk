package app.betterdesk.core

import java.util.concurrent.atomic.AtomicLong

/** A dropped predictive frame invalidates its successors until a newer independent frame. */
class VideoRecovery(private val requestKeyFrame: () -> Unit) {
    private val droppedThrough = AtomicLong(0)
    private val keyFrame = AtomicLong(0)
    private val lastRequest = AtomicLong(0)
    fun discard(frame: Packet.Frame) {
        if (frame.codec != "avc") return
        droppedThrough.accumulateAndGet(frame.id, ::maxOf)
        val now = System.nanoTime()
        val previous = lastRequest.get()
        if (now - previous > 100_000_000 && lastRequest.compareAndSet(previous, now)) requestKeyFrame()
    }
    fun accepts(frame: Packet.Frame): Boolean {
        if (frame.codec != "avc") return true
        if (frame.keyFrame && frame.id > droppedThrough.get()) keyFrame.set(frame.id)
        return keyFrame.get() > droppedThrough.get()
    }
}
