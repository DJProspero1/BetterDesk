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
        val viewer = object : RemotePlatform by desktop {
            override suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit) = error("Viewer must not capture the PC")
        }
        runBlocking {
            val session = HelpSession(viewer, { listOf("10.0.2.2") }, InetAddress.getLoopbackAddress())
            try {
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
                    adbCommand("shell", "am", "start", "-a", "android.settings.SETTINGS")
                    repeat(12) { index ->
                        if (index % 2 == 0) adbCommand("shell", "input", "swipe", "500", "1800", "500", "500", "500")
                        else adbCommand("shell", "input", "swipe", "500", "500", "500", "1800", "500")
                    }
                    adbCommand("shell", "am", "start", "-n", "app.betterdesk/.MainActivity")
                }
                monitor.cancelAndJoin()
                assertEquals(Stage.VIEWING, session.state.value.stage, "Switching apps must preserve the connection")
                assertTrue(times.size > 10, "Receive moving screen frames in the background")
                val fps = (times.size - 1) * 1e9 / (times.last() - times.first())
                File("build/reports/android-motion.txt").writeText("Android emulator -> Windows, settings scroll sample: %.1f received FPS (%d frames). Includes adb command gaps. Background switching preserved the session.\n".format(fps, times.size))
                withContext(Dispatchers.Main) { session.stop() }
                withTimeout(8000) {
                    while (withContext(Dispatchers.IO) { adbText("shell", "dumpsys", "media_projection").contains("TYPE_SCREEN_CAPTURE") }) delay(150)
                }
                assertEquals(processId, withContext(Dispatchers.IO) { adbText("shell", "pidof", "app.betterdesk") }, "Remote disconnect must not crash/restart Android")
            } finally { withContext(Dispatchers.Main) { session.dispose() } }
        }
    }
}
