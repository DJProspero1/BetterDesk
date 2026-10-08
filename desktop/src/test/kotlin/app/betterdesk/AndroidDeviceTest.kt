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
                withContext(Dispatchers.Main) { session.stop() }
            } finally { withContext(Dispatchers.Main) { session.dispose() } }
        }
    }
}
