package app.betterdesk

import app.betterdesk.core.Packet
import app.betterdesk.desktop.DesktopPlatform
import app.betterdesk.desktop.AvcDecoder
import androidx.compose.ui.graphics.toPixelMap
import org.bytedeco.ffmpeg.global.avcodec.avcodec_license
import org.junit.Test
import org.junit.Assume.assumeTrue
import kotlin.test.*

class AvcDecoderTest {
    @Test fun `independent AVC frames decode after dropped frames and reject false dimensions`() {
        // Synthetic solid-green IDR, generated with FFmpeg's color source. No screen recording.
        val bytes = javaClass.getResourceAsStream("/green-64x64-idr.h264")!!.use { it.readBytes() }
        AvcDecoder().use { decoder ->
            assertTrue(avcodec_license().string.contains("LGPL"), "Package the non-GPL FFmpeg artifact")
            val image = decoder.decode(Packet.Frame(1, 64, 64, bytes, "avc-intra"))
            assertEquals(64, image.width)
            val color = image.toPixelMap()[32, 32]
            assertTrue(color.green > .4f && color.red < .1f && color.blue < .1f)
            assertEquals(64, decoder.decode(Packet.Frame(30, 64, 64, bytes, "avc-intra")).height)
            assertFails { decoder.decode(Packet.Frame(31, 63, 64, bytes, "avc-intra")) }
        }
    }

    @Test fun `Windows platform dispatches AVC and fails closed on invalid dimensions`() {
        assumeTrue("Windows platform integration requires Windows", System.getProperty("os.name").startsWith("Windows"))
        val bytes = javaClass.getResourceAsStream("/green-64x64-idr.h264")!!.use { it.readBytes() }
        val platform = DesktopPlatform()
        try {
            assertEquals(64, platform.decode(Packet.Frame(1, 64, 64, bytes, "avc-intra"))?.height)
            assertNull(platform.decode(Packet.Frame(2, 63, 64, bytes, "avc-intra")))
        } finally { platform.stopSharing() }
    }
}
