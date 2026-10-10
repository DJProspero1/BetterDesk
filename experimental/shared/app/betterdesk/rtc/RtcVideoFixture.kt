package app.betterdesk.rtc

/** Known synthetic pixels only. No app screen/audio permission or capture API. */
object RtcVideoFixture {
    private fun values(reverse: Boolean) = if (reverse) listOf(200, 160, 80, 40) else listOf(40, 80, 160, 200)
    fun frame(reverse: Boolean): RtcVideoFrame {
        val width = 96; val height = 64; val quarters = values(reverse)
        val y = ByteArray(width * height) { offset ->
            quarters[(if (offset / width >= height / 2) 2 else 0) + (if (offset % width >= width / 2) 1 else 0)].toByte()
        }
        return RtcVideoFrame(width, height, y, ByteArray(y.size / 4) { 128.toByte() }, ByteArray(y.size / 4) { 128.toByte() })
    }
    fun checkSample(sample: RtcVideoSample, reverse: Boolean) {
        check(sample.width == 96 && sample.height == 64 && sample.rotation == 0)
        check(sample.quarters.size == 4 && values(reverse).zip(sample.quarters).all { (before, after) -> kotlin.math.abs(before - after) <= 12 })
    }
    fun checkCounters(counters: RtcVideoCounters) {
        check(counters.encoded >= 3 && counters.decoded >= 3 && counters.sentBytes > 0 && counters.receivedBytes > 0)
    }
}
