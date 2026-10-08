package app.betterdesk

import app.betterdesk.desktop.DesktopPlatform
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue

/** Opt-in local screen capture benchmark. Keeps timing/size statistics only, never screen pixels. */
class CaptureBenchmarkTest {
    @Test fun `measure native desktop capture throughput`() {
        assumeTrue(System.getenv("BETTERDESK_CAPTURE_BENCHMARK") == "1")
        val timestamps = CopyOnWriteArrayList<Long>()
        val sizes = CopyOnWriteArrayList<Int>()
        val platform = DesktopPlatform()
        runBlocking {
            try {
                assertTrue(platform.startSharing({ frame -> timestamps.add(System.nanoTime()); sizes.add(frame.jpeg.size) }, {}))
                delay(12_000)
            } finally { platform.stopSharing() }
        }
        assertTrue(timestamps.size > 2)
        val fps = (timestamps.size - 1) * 1e9 / (timestamps.last() - timestamps.first())
        val warm = timestamps.filter { it - timestamps.first() >= 2_000_000_000 }
        val steady = (warm.size - 1) * 1e9 / (warm.last() - warm.first())
        val output = "Native Windows capture: %.1f FPS overall, %.1f FPS after 2s warmup, %d frames, %.0f KB average JPEG. 12 second sample; no pixel data stored.\n".format(fps, steady, timestamps.size, sizes.average() / 1024)
        File("build/reports/desktop-capture.txt").apply { parentFile.mkdirs(); writeText(output) }
        println(output)
    }
}
