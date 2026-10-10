package app.betterdesk

import app.betterdesk.core.*
import app.betterdesk.rtc.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.*

/** Explicit opt-in: installs the final proof APKs on the selected dedicated device. */
class RtcAndroidHostTest {
    @Test fun `desktop and Android native peers interoperate over authenticated bootstrap`() {
        interoperate(false)
    }
    @Test fun `desktop and Android native video tracks interoperate over authenticated bootstrap`() {
        interoperate(true)
    }
    private fun interoperate(video: Boolean) {
        val serial = System.getenv("BETTERDESK_RTC_ANDROID_SERIAL")
        assumeTrue("Dedicated Android proof device is opt-in", !serial.isNullOrBlank())
        require(serial!!.matches(Regex("[A-Za-z0-9._:-]{1,100}")))
        val sdk = System.getenv("ANDROID_HOME") ?: error("Set ANDROID_HOME for the proof device")
        val adb = File(sdk, "platform-tools/" + if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb")
        require(adb.isFile)
        val repo = File(System.getProperty("user.dir")).let { if (File(it, "settings.gradle.kts").isFile) it else it.parentFile }
        val app = File(repo, "android/build/outputs/apk/debug/android-debug.apk")
        val tests = File(repo, "android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk")
        require(app.isFile && tests.isFile)

        fun command(vararg arguments: String, input: String? = null): String {
            val process = ProcessBuilder(listOf(adb.absolutePath, "-s", serial) + arguments).redirectErrorStream(true).start()
            process.outputStream.use { if (input != null) it.write(input.toByteArray()) }
            try {
                // These control commands produce bounded metadata, never SDP or config.
                check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "ADB proof command failed" }
                val bytes = process.inputStream.readNBytes(32_769)
                check(bytes.size <= 32_768)
                return bytes.toString(Charsets.UTF_8)
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        check(command("shell", "getprop", "sys.boot_completed").trim() == "1") { "Android proof device has not completed boot" }
        check(command("install", "-r", app.absolutePath).contains("Success"))
        check(command("install", "-r", tests.absolutePath).contains("Success"))
        val identity = TlsIdentity.create()
        val listener = identity.context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.soTimeout = 20_000
        val peer = try { DesktopRtcPeer(video) } catch (error: Throwable) { listener.close(); throw error }
        val port = listener.localPort
        var instrumentation: Process? = null
        try {
            command("reverse", "tcp:$port", "tcp:$port")
            val config = RtcProofConfig(port, identity.fingerprint, Invitation.secret(), Invitation.secret(), "1".repeat(32), "2".repeat(32), System.currentTimeMillis() + 30_000, video)
            // Stream private parameters to Android private cache; no secret-bearing CLI arguments.
            command("shell", "run-as", "app.betterdesk", "sh", "-c", "'umask 077; mkdir -p cache; cat > cache/rtc-proof.json'", input = Json.encodeToString(config))
            instrumentation = ProcessBuilder(adb.absolutePath, "-s", serial, "shell", "am", "instrument", "-w", "-e", "class",
                "app.betterdesk.RtcAndroidProofTest#" + if (video) "nativeDesktopVideoInteroperation" else "nativeDesktopInteroperation", "app.betterdesk.test/androidx.test.runner.AndroidJUnitRunner")
                .redirectErrorStream(true).start()
            (listener.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 15_000
                val wire = Wire(socket)
                val hello = (wire.read() as Packet.Control).message
                val protocol = if (video) 2 else 1
                check(hello.type == "rtcProofHello" && hello.protocol == protocol && TlsIdentity.constantEquals(hello.secret, config.secret))
                val offer = peer.offer().get(15, TimeUnit.SECONDS)
                val signal = RtcSignal(config.session, config.hostId, config.androidId, 1, config.expires, true, RtcDescription.validate(offer, video), offer, protocol)
                wire.send(Message("rtcProofOffer", text = signal.encode()))
                val answer = (wire.read() as Packet.Control).message
                check(answer.type == "rtcProofAnswer")
                val authenticated = RtcSignal.decode(answer.text).validate(config.session, config.androidId, config.hostId, 1, config.expires, false, video = video)
                peer.acceptAnswer(authenticated).get(15, TimeUnit.SECONDS)
                peer.opened.get(15, TimeUnit.SECONDS)
                peer.send(ByteArray(RtcPeer.MAX_BYTES) { (it % 251).toByte() }).get(5, TimeUnit.SECONDS)
                assertEquals("Olá · synthetic Android", peer.receive(10_000).toString(Charsets.UTF_8))
                if (video) {
                    repeat(3) {
                        assertTrue(peer.sendVideo(RtcVideoFixture.frame(false)).get(2, TimeUnit.SECONDS))
                        assertContentEquals(byteArrayOf(1), peer.receive(5000))
                    }
                    repeat(3) { RtcVideoFixture.checkSample(peer.receiveVideo(5000), true); peer.send(byteArrayOf(2)).get(2, TimeUnit.SECONDS) }
                    RtcVideoFixture.checkCounters(peer.videoCounters().get(2, TimeUnit.SECONDS))
                }
                check((wire.read() as Packet.Control).message.type == "rtcProofDone")
                wire.send(Message("rtcProofDone"))
            }
            check(instrumentation.waitFor(30, TimeUnit.SECONDS) && instrumentation.exitValue() == 0)
            val output = instrumentation.inputStream.readNBytes(32_769).toString(Charsets.UTF_8)
            check(output.length <= 32_768 && output.contains("OK (1 test)") && !output.contains("FAILURES")) { "Android RTC proof failed" }
        } finally {
            if (instrumentation?.isAlive == true) instrumentation.destroyForcibly()
            try { listener.close() } finally {
                try { peer.close() } finally {
                    runCatching { command("reverse", "--remove", "tcp:$port") }
                    runCatching { command("shell", "run-as", "app.betterdesk", "rm", "-f", "cache/rtc-proof.json") }
                }
            }
            assertTrue(peer.awaitClosed(5000))
        }
    }
}
