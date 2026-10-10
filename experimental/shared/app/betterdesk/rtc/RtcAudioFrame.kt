package app.betterdesk.rtc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Copied 10 ms of 48 kHz mono PCM16, for an externally fed synthetic source. */
class RtcAudioFrame(bytes: ByteArray, val timestampNs: Long = System.nanoTime()) {
    private val pcm: ByteArray
    init { require(bytes.size == BYTES && timestampNs > 0); pcm = bytes.copyOf() }
    fun copyInto(target: ByteBuffer) { require(target.remaining() == BYTES); target.put(pcm) }
    fun copyBytes() = pcm.copyOf()
    override fun toString() = "RtcAudioFrame(redacted)"
    companion object { const val RATE = 48_000; const val FRAMES = 480; const val BYTES = FRAMES * 2 }
}

class RtcAudioSample(val rate: Int, val channels: Int, val peak: Int) {
    override fun toString() = "RtcAudioSample(redacted)"
    companion object {
        fun read(bytes: ByteArray, bits: Int, rate: Int, channels: Int, frames: Int): RtcAudioSample {
            require(bits == 16 && rate in 8000..48000 && channels in 1..2 && frames in 1..960)
            val size = frames * channels * 2
            require(bytes.size >= size && size <= 3840)
            val pcm = ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN)
            var peak = 0
            while (pcm.remaining() >= 2) peak = maxOf(peak, kotlin.math.abs(pcm.short.toInt()))
            return RtcAudioSample(rate, channels, peak)
        }
    }
}

data class RtcAudioCounters(val sentPackets: Long, val receivedPackets: Long, val sentBytes: Long, val receivedBytes: Long)

object RtcAudioFixture {
    fun frame(index: Int): RtcAudioFrame {
        val bytes = ByteBuffer.allocate(RtcAudioFrame.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(RtcAudioFrame.FRAMES) { offset ->
            val sample = (6000 * kotlin.math.sin(2 * Math.PI * 440 * (index.toLong() * RtcAudioFrame.FRAMES + offset) / RtcAudioFrame.RATE)).toInt()
            bytes.putShort(sample.toShort())
        }
        return RtcAudioFrame(bytes.array())
    }
    fun checkSample(sample: RtcAudioSample) { check(sample.rate == 48000 && sample.channels in 1..2 && sample.peak >= 1000) }
    fun checkCounters(counters: RtcAudioCounters) { check(counters.sentPackets > 0 && counters.receivedPackets > 0 && counters.sentBytes > 0 && counters.receivedBytes > 0) }
}
