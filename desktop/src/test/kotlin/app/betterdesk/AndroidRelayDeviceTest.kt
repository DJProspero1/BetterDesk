package app.betterdesk

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import app.betterdesk.core.*
import app.betterdesk.desktop.DesktopPlatform
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Opt-in public relay tests against the locally installed Android test APK. */
class AndroidRelayDeviceTest {
    private val adb = File(System.getenv("LOCALAPPDATA"),"Android/Sdk/platform-tools/adb.exe")
    private val device = System.getenv("BETTERDESK_ANDROID_SERIAL") ?: "emulator-5554"
    private val testAudio = System.getenv("BETTERDESK_COMPARE_VIDEO_ONLY") != "1"
    private fun command(vararg args: String): String {
        val child=ProcessBuilder(listOf(adb.path,"-s",device)+args).redirectErrorStream(true).start()
        val output=child.inputStream.bufferedReader().readText();child.waitFor();return output.trim()
    }
    private fun instrument(id: String, mode: String, code: String? = null): Process {
        val args=mutableListOf(adb.path,"-s",device,"shell","am","instrument","-w","-e","class","app.betterdesk.RelayDeviceTest","-e","relayTest","1","-e","runId",id,"-e","mode",mode)
        if(code!=null)args.addAll(listOf("-e","code",code))
        if(!testAudio)args.addAll(listOf("-e","videoOnly","1"))
        args.add("app.betterdesk.test/androidx.test.runner.AndroidJUnitRunner")
        return ProcessBuilder(args).redirectErrorStream(true).start()
    }
    private suspend fun until(timeout: Long = 45000, condition: () -> Boolean) { withTimeout(timeout) { while(!condition())delay(100) } }
    private fun verify(process: Process, report: File) {
        check(process.waitFor(150,TimeUnit.SECONDS)) { "Android test did not finish" }
        val output=process.inputStream.bufferedReader().readText();report.writeText(output)
        assertTrue(output.contains("OK (1 test)"),"Android instrumentation failed; see ${report.name}")
    }
    @Test fun `Android capture reaches Windows through Railway and notification clears`() {
        assumeTrue(System.getenv("BETTERDESK_ANDROID_RELAY") == "1")
        runBlocking {
            val native = DesktopPlatform()
            val soundPackets = java.util.concurrent.atomic.AtomicInteger()
            val tonePackets = java.util.concurrent.atomic.AtomicInteger()
            val receiver = object : RemotePlatform by native {
                override val receivesAudio=testAudio
                override fun playAudio(audio: Packet.Audio) {
                    soundPackets.incrementAndGet()
                    var sine = 0.0; var cosine = 0.0
                    repeat(960) { n ->
                        val sample = ((audio.pcm[n*4].toInt() and 255) or (audio.pcm[n*4+1].toInt() shl 8)).toShort().toInt()
                        sine += sample*kotlin.math.sin(2*Math.PI*660*n/48000)
                        cosine += sample*kotlin.math.cos(2*Math.PI*660*n/48000)
                    }
                    if(kotlin.math.hypot(sine,cosine)/960>100)tonePackets.incrementAndGet()
                    native.playAudio(audio)
                }
            }
            val session=HelpSession(receiver, localDiscovery=false)
            val id=UUID.randomUUID().toString();val android=instrument(id,"share")
            var window: javax.swing.JFrame?=null
            File("build/reports").mkdirs();File("build/screenshots").mkdirs()
            try {
                var code=""
                withTimeout(45000) {
                    while(!code.matches(Regex("[0-9]{12}"))) {
                        code=withContext(Dispatchers.IO) { command("exec-out","run-as","app.betterdesk","cat","files/relay-code-$id") }
                        if(!code.matches(Regex("[0-9]{12}")))delay(200)
                    }
                }
                withContext(Dispatchers.Main) {
                    session.connectCode(code)
                    val panel=androidx.compose.ui.awt.ComposePanel().apply { setContent { app.betterdesk.ui.BetterDeskTheme { app.betterdesk.ui.BetterDeskApp(session) } } }
                    window=javax.swing.JFrame("BetterDesk Android relay test").apply { contentPane=panel;setSize(450,850);isVisible=true }
                }
                until(60000) { session.state.value.stage==Stage.VIEWING }
                assertTrue(session.state.value.viaRelay)
                delay(3000);session.performance.reset();delay(30000)
                val stats=session.performance.snapshot()
                assertEquals(testAudio,session.state.value.audioAvailable)
                if(testAudio) {
                    assertTrue(soundPackets.get()>50,"Android playback audio did not reach Windows")
                    assertTrue(tonePackets.get()>5,"Known Android fixture tone did not reach Windows")
                }
                if(testAudio) File("build/reports/android-relay-audio.txt").writeText("Android playback -> Railway pinned TLS -> Windows native playback: ${soundPackets.get()} PCM packets; ${tonePackets.get()} packets contain the synthetic 660 Hz tone. No audio saved.\n")
                File("build/reports/android-relay-performance.txt").writeText("Android emulator -> Windows through public Railway, 30s motion sample: ${stats.receivedFps} received FPS; ${stats.presentedFps} drawn FPS; P95 draw gap ${stats.p95DrawGapMs} ms; payload ${stats.encodedMbps} Mbps; decode ${stats.decodeMs} ms. Not physical-phone or display-scanout measurement.\n")
                File("build/reports/android-relay-performance.txt").copyTo(File("build/reports/0.6-${if(testAudio) "audio-enabled" else "video-only"}.txt"),overwrite=true)
                val bitmap=session.state.value.image!!
                Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image->image.encodeToData(EncodedImageFormat.PNG)!!.use { File("build/screenshots/android-relay-received.png").writeBytes(it.bytes) } }
                withContext(Dispatchers.Main) { session.stop() }
                withContext(Dispatchers.IO) { verify(android,File("build/reports/android-relay-share.txt")) }
            } finally {
                withContext(Dispatchers.Main) { session.dispose();window?.dispose() }
                if(android.isAlive) { command("shell","am","force-stop","app.betterdesk");android.destroy() }
                command("shell","am","force-stop","app.betterdesk.motionfixture")
            }
        }
    }
    @Test fun `Android connects to Windows relay fixture and sends remote input`() {
        assumeTrue(System.getenv("BETTERDESK_ANDROID_RELAY") == "1")
        runBlocking {
            val received=AtomicBoolean(false)
            val nativeReceiver=DesktopPlatform()
            // Isolated fixture: synthetic frames and an input sink avoid touching unrelated PC apps.
            val platform=object:RemotePlatform {
                override val isAndroid=false
                override val defaultName="Windows relay test"
                override val preferredIncomingCodec="avc"
                private var frames: Job?=null
                private var audio: Job?=null
                override fun controlAvailable()=true
                override fun openControlSettings()=Unit
                override suspend fun startSharing(onFrame:(Packet.Frame)->Unit,onStopped:()->Unit):Boolean {
                    frames=CoroutineScope(Dispatchers.IO).launch {
                        var id=1L
                        while(isActive) {
                            val bitmap=java.awt.image.BufferedImage(640,400,java.awt.image.BufferedImage.TYPE_INT_RGB)
                            bitmap.createGraphics().apply {
                                color=java.awt.Color(234,242,235);fillRect(0,0,640,400)
                                color=java.awt.Color(34,99,78);font=java.awt.Font("SansSerif",java.awt.Font.BOLD,28)
                                drawString("Windows relay test",35,70);fillRect(((id*6)%500).toInt(),150,120,90)
                                if(received.get())drawString("Input received",35,340);dispose()
                            }
                            val bytes=java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(bitmap,"jpg",bytes)
                            onFrame(Packet.Frame(id++,640,400,bytes.toByteArray()));delay(16)
                        }
                    };return true
                }
                override fun stopSharing(){frames?.cancel();frames=null;nativeReceiver.stopSharing()}
                override fun startAudio(onAudio:(Packet.Audio)->Unit):Boolean {
                    audio=CoroutineScope(Dispatchers.IO).launch {
                        var id=1L;var sample=0L
                        while(isActive) {
                            val pcm=ByteArray(Wire.AUDIO_BYTES)
                            repeat(960) { n ->
                                val value=(1800*kotlin.math.sin(2*Math.PI*660*sample++/48000)).toInt()
                                repeat(2) { c ->pcm[n*4+c*2]=value.toByte();pcm[n*4+c*2+1]=(value shr 8).toByte() }
                            }
                            onAudio(Packet.Audio(id++,pcm));delay(20)
                        }
                    };return true
                }
                override fun stopAudioCapture(){audio?.cancel();audio=null}
                override fun applyInput(input:Message){if(input.action=="text"&&input.text=="BetterDesk relay test")received.set(true)}
                override fun decode(frame:Packet.Frame):ImageBitmap?=nativeReceiver.decode(frame)
            }
            val session=HelpSession(platform,localDiscovery=false);var android:Process?=null
            try {
                withContext(Dispatchers.Main){session.prepareDeviceCode()}
                until { session.state.value.deviceCode.length==12 }
                android=instrument(UUID.randomUUID().toString(),"view",session.state.value.deviceCode)
                until { session.state.value.stage==Stage.INVITATION }
                assertTrue(session.state.value.viaRelay)
                withContext(Dispatchers.Main){
                    session.allowHelp()
                    if (session.state.value.stage == Stage.REMEMBER) session.finishRemember(false)
                }
                try { until(75000) { check(android.isAlive) { "Android instrumentation ended before input" }; received.get() } }
                catch(e: Exception) { error("Desktop relay state ${session.state.value.stage}; ${session.failureSummary}; Android input not received") }
                until { session.state.value.switchState=="incoming" }
                withContext(Dispatchers.Main){session.acceptSwitch()}
                until(60000) { session.state.value.stage==Stage.VIEWING }
                assertNotNull(session.state.value.image)
                withContext(Dispatchers.Main){session.requestSwitch()}
                until { session.state.value.stage==Stage.SHARING }
                until { session.state.value.stage==Stage.ENDED }
                withContext(Dispatchers.IO){verify(android,File("build/reports/android-relay-view.txt"))}
            }finally{
                withContext(Dispatchers.Main){session.dispose()}
                if(android?.isAlive==false) {
                    val output=android.inputStream.bufferedReader().readText()
                    if(output.isNotBlank())File("build/reports/android-relay-view.txt").writeText(output)
                }
                if(android?.isAlive==true){command("shell","am","force-stop","app.betterdesk");android.destroy()}
            }
        }
    }
}
