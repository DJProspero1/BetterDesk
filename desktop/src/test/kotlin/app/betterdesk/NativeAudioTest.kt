package app.betterdesk

import app.betterdesk.desktop.WasapiAudio
import app.betterdesk.core.Wire
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.*

/** Opt-in: plays a brief quiet synthetic tone and verifies native render-loopback capture. */
class NativeAudioTest {
    @Test fun `WASAPI captures native playback as bounded stereo PCM packets`() {
        assumeTrue(System.getenv("BETTERDESK_NATIVE_AUDIO") == "1")
        val capture = WasapiAudio()
        val packets = AtomicInteger(); val signal = AtomicInteger()
        val format = AudioFormat(48000f, 16, 2, true, false)
        val speaker = AudioSystem.getSourceDataLine(format)
        try {
            assertTrue(capture.start { audio ->
                assertEquals(Wire.AUDIO_BYTES, audio.pcm.size)
                packets.incrementAndGet()
                var sine = 0.0; var cosine = 0.0
                repeat(960) { n ->
                    val sample = ((audio.pcm[n * 4].toInt() and 255) or (audio.pcm[n * 4 + 1].toInt() shl 8)).toShort().toInt()
                    sine += sample * kotlin.math.sin(2 * Math.PI * 660 * n / 48000)
                    cosine += sample * kotlin.math.cos(2 * Math.PI * 660 * n / 48000)
                }
                if (kotlin.math.hypot(sine, cosine) / 960 > 100) signal.incrementAndGet()
            }, capture.failure)
            speaker.open(format, Wire.AUDIO_BYTES * 4); speaker.start()
            var sampleIndex = 0
            repeat(75) {
                val pcm = ByteArray(Wire.AUDIO_BYTES)
                repeat(960) { n ->
                    val value = (1800 * kotlin.math.sin(2 * Math.PI * 660 * sampleIndex++ / 48000)).toInt()
                    repeat(2) { c -> pcm[n * 4 + c * 2] = value.toByte(); pcm[n * 4 + c * 2 + 1] = (value shr 8).toByte() }
                }
                speaker.write(pcm, 0, pcm.size)
            }
            speaker.drain(); Thread.sleep(150)
            assertTrue(packets.get() >= 10, "WASAPI packets missing: ${capture.failure}")
            assertTrue(signal.get() >= 5, "Known playback tone was not present in loopback audio")
            capture.stop(); Thread.sleep(100)
            val stopped = packets.get(); Thread.sleep(100); assertEquals(stopped, packets.get())
            // Rapid stop/restart must not let an old worker terminate the new one.
            repeat(3) {
                val restarted = AtomicInteger()
                assertTrue(capture.start { restarted.incrementAndGet() }, capture.failure)
                repeat(8) { speaker.write(ByteArray(Wire.AUDIO_BYTES),0,Wire.AUDIO_BYTES) }
                Thread.sleep(100)
                assertTrue(restarted.get()>0,"Restarted WASAPI worker stopped unexpectedly")
                capture.stop()
            }
        } finally { capture.stop(); speaker.stop(); speaker.close() }
    }
}
