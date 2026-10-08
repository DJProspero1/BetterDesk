package app.betterdesk

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import app.betterdesk.core.*
import app.betterdesk.desktop.DesktopPlatform
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.net.InetAddress
import kotlin.test.*

/** Opt-in device test. Approve the genuine screen-sharing prompts in the emulator. */
class AndroidDeviceTest {
    @Test fun `emulator sends real Android capture over pinned TLS`() {
        assumeTrue(System.getenv("BETTERDESK_ANDROID_TEST") == "1")
        val adb = File(System.getenv("LOCALAPPDATA"), "Android/Sdk/platform-tools/adb.exe")
        val device = System.getenv("BETTERDESK_ANDROID_SERIAL") ?: "emulator-5554"
        val desktop = DesktopPlatform()
        val motionFixture = System.getenv("BETTERDESK_MOTION_FIXTURE") == "1"
        val unique = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val measuring = java.util.concurrent.atomic.AtomicBoolean(false)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val receivedCodec = java.util.concurrent.atomic.AtomicReference("")
        val viewer = object : RemotePlatform by desktop {
            override suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit) = error("Viewer must not capture the PC")
            override fun decode(frame: Packet.Frame): ImageBitmap? {
                receivedCodec.set(frame.codec)
                if (measuring.get()) unique.add(java.util.Base64.getEncoder().encodeToString(digest.digest(frame.jpeg)))
                return desktop.decode(frame)
            }
        }
        runBlocking {
            val session = HelpSession(viewer, { listOf("10.0.2.2") }, InetAddress.getLoopbackAddress())
            var window: javax.swing.JFrame? = null
            try {
                if (motionFixture) withContext(Dispatchers.Main) {
                    val panel = androidx.compose.ui.awt.ComposePanel().apply { setContent { app.betterdesk.ui.BetterDeskTheme { app.betterdesk.ui.BetterDeskApp(session) } } }
                    window = javax.swing.JFrame("BetterDesk Android receiver benchmark").apply { contentPane = panel; setSize(440, 880); isVisible = true }
                }
                withContext(Dispatchers.Main) { session.createInvitation("Local test") }
                withTimeout(10_000) { while (session.state.value.stage != Stage.WAITING) delay(50) }
                val link = session.state.value.invitation!!.link()
                // Do not print the secret-bearing command or write the invitation to disk.
                val process = ProcessBuilder(adb.path, "-s", device, "shell", "am start -n app.betterdesk/.MainActivity -a android.intent.action.VIEW -d '$link'").redirectErrorStream(true).start()
                process.inputStream.readBytes(); check(process.waitFor() == 0)
                withTimeout(150_000) { while (session.state.value.stage != Stage.VIEWING) delay(100) }
                val image = session.state.value.image!!
                assertTrue(image.width >= 100 && image.height >= 100)
                File("build/screenshots").mkdirs()
                File("build/screenshots/android-capture-received.png").writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                assertFalse(session.state.value.remoteControl, "Choose screen sharing only for this test")
                fun adbText(vararg args: String): String {
                    val child = ProcessBuilder(listOf(adb.path, "-s", device) + args).redirectErrorStream(true).start()
                    val output = child.inputStream.bufferedReader().readText()
                    child.waitFor()
                    return output.trim()
                }
                val processId = withContext(Dispatchers.IO) { adbText("shell", "pidof", "app.betterdesk") }
                assertTrue(processId.isNotBlank())
                withTimeout(8000) {
                    while (!withContext(Dispatchers.IO) { adbText("shell", "cmd", "notification", "list").contains("|app.betterdesk|1|") }) delay(100)
                }
                assertFalse(withContext(Dispatchers.IO) { adbText("shell", "cmd", "notification", "list").contains("|app.betterdesk|2|") }, "No duplicate availability notification while sharing")
                val times = java.util.concurrent.CopyOnWriteArrayList<Long>()
                val monitor = launch {
                    var previous = 0L
                    session.state.collect { state -> if (state.frame > previous) { previous = state.frame; times.add(System.nanoTime()) } }
                }
                withContext(Dispatchers.IO) {
                    fun adbCommand(vararg args: String) {
                        val child = ProcessBuilder(listOf(adb.path, "-s", device) + args).redirectErrorStream(true).start()
                        child.inputStream.readBytes(); check(child.waitFor() == 0)
                    }
                    if (motionFixture) {
                        adbCommand("shell", "am", "start", "-n", "app.betterdesk.motionfixture/.MotionActivity")
                        delay(2500)
                        times.clear(); unique.clear(); session.performance.reset(); measuring.set(true)
                        delay(30_000)
                        measuring.set(false)
                        val stats = session.performance.snapshot()
                        File("build/reports/android-render-benchmark.txt").writeText("Continuous Android motion -> Windows (${receivedCodec.get()}): ${stats.receivedFps} received FPS, ${stats.presentedFps} distinct frame IDs drawn/s, ${unique.size / stats.seconds} distinct encoded frames/s over ${stats.seconds}s. P95 draw gap ${stats.p95DrawGapMs}ms; payload ${stats.encodedMbps} Mbps; average decode ${stats.decodeMs}ms.\n")
                        val finalImage = session.state.value.image!!
                        Image.makeFromBitmap(finalImage.asSkiaBitmap()).use { bitmap ->
                            bitmap.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File("build/screenshots/android-motion-received.png").writeBytes(data.bytes) }
                        }
                    } else {
                        adbCommand("shell", "am", "start", "-a", "android.settings.SETTINGS")
                        repeat(12) { index ->
                            if (index % 2 == 0) adbCommand("shell", "input", "swipe", "500", "1800", "500", "500", "500")
                            else adbCommand("shell", "input", "swipe", "500", "500", "500", "1800", "500")
                        }
                    }
                    adbCommand("shell", "am", "start", "-n", "app.betterdesk/.MainActivity")
                }
                monitor.cancelAndJoin()
                assertEquals(Stage.VIEWING, session.state.value.stage, "Switching apps must preserve the connection")
                assertTrue(times.size > 10, "Receive moving screen frames in the background")
                val fps = (times.size - 1) * 1e9 / (times.last() - times.first())
                File("build/reports/android-motion.txt").writeText("Android emulator -> Windows, ${if (motionFixture) "continuous fixture" else "settings scroll"} sample: %.1f received FPS (%d frames). Background switching preserved the session.\n".format(fps, times.size))
                if (System.getenv("BETTERDESK_NOTIFICATION_STOP") == "1") {
                    // The operator presses the genuine notification Disconnect action.
                    withTimeout(60_000) { while (session.state.value.stage != Stage.ENDED) delay(100) }
                } else withContext(Dispatchers.Main) { session.stop() }
                withTimeout(8000) {
                    while (withContext(Dispatchers.IO) { adbText("shell", "dumpsys", "media_projection").contains("TYPE_SCREEN_CAPTURE") }) delay(150)
                }
                assertEquals(processId, withContext(Dispatchers.IO) { adbText("shell", "pidof", "app.betterdesk") }, "Remote disconnect must not crash/restart Android")
                withTimeout(8000) {
                    while (withContext(Dispatchers.IO) { adbText("shell", "cmd", "notification", "list").contains("|app.betterdesk|") }) delay(100)
                }
                File("build/reports/android-notification.txt").writeText("Sharing notification present during capture; duplicate availability notification absent; all BetterDesk notifications cleared after ${if (System.getenv("BETTERDESK_NOTIFICATION_STOP") == "1") "notification Disconnect" else "remote disconnect"}. Android process survived.\n")
            } finally { withContext(Dispatchers.Main) { session.dispose(); window?.dispose() } }
        }
    }
}
