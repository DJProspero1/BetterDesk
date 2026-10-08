package app.betterdesk.core

/** Bounds encoded frames in flight, so a slow connection drops old capture instead of queuing video. */
class FrameWindow(private val capacity: Int = 2) {
    private val pending = linkedSetOf<Long>()
    @Synchronized fun offer(id: Long): Boolean {
        if (pending.size >= capacity || id <= 0) return false
        pending.add(id)
        return true
    }
    @Synchronized fun acknowledge(id: Long) { pending.remove(id) }
    @Synchronized fun clear() { pending.clear() }
}
