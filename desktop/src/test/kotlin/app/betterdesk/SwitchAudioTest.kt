package app.betterdesk

import androidx.compose.ui.graphics.ImageBitmap
import app.betterdesk.core.*
import app.betterdesk.desktop.StereoPcm
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SwitchAudioTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val sessions = mutableListOf<HelpSession>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { runBlocking(dispatcher) { sessions.forEach(HelpSession::dispose) }; Dispatchers.resetMain(); dispatcher.close() }
    private class Platform(override val isAndroid: Boolean = false) : RemotePlatform {
        override val defaultName = "Synthetic test device"
        override val receivesAudio = true
        var starts = 0; var controls = true; var allowCapture = true; var inputs = 0
        var frames: ((Packet.Frame) -> Unit)? = null
        var audio: ((Packet.Audio) -> Unit)? = null
        val played = java.util.concurrent.CopyOnWriteArrayList<Packet.Audio>()
        override fun controlAvailable() = controls
        override fun openControlSettings() = Unit
        override suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit): Boolean {
            starts++; if (allowCapture) frames = onFrame; return allowCapture
        }
        override fun stopSharing() { frames = null }
        override fun startAudio(onAudio: (Packet.Audio) -> Unit): Boolean { audio = onAudio; return true }
        override fun stopAudioCapture() { audio = null }
        override fun playAudio(audio: Packet.Audio) { played += audio }
        override fun applyInput(input: Message) { inputs++ }
        override fun decode(frame: Packet.Frame) = ImageBitmap(frame.width, frame.height)
        fun frame(id: Long = 1) { frames?.invoke(Packet.Frame(id, 2, 2, byteArrayOf(1))) }
        fun sound(id: Long = 1) { audio?.invoke(Packet.Audio(id, ByteArray(Wire.AUDIO_BYTES) { 3 })) }
    }
    private fun session(platform: Platform) = HelpSession(platform, { listOf("127.0.0.1") }, java.net.InetAddress.getLoopbackAddress(), relayFactory = { null }).also(sessions::add)
    private suspend fun until(condition: () -> Boolean) { withTimeout(12000) { while (!condition()) delay(20) } }
    private suspend fun connect(source: HelpSession, viewer: HelpSession, platform: Platform) {
        viewer.createInvitation("Synthetic viewer"); until { viewer.state.value.stage == Stage.WAITING }
        source.acceptInvitation(viewer.state.value.invitation!!.link()); source.allowHelp()
        until { source.state.value.stage == Stage.SHARING }
        platform.frame(); until { viewer.state.value.stage == Stage.VIEWING }
    }

    @Test fun `either endpoint can request a switch but approval is mandatory and decline preserves the stream`() = runBlocking(dispatcher) {
        val sp = Platform(); val vp = Platform(); val source = session(sp); val viewer = session(vp)
        connect(source, viewer, sp)
        source.requestSwitch(); until { viewer.state.value.switchState == "incoming" }
        assertEquals(0, vp.starts); assertEquals(Stage.SHARING, source.state.value.stage)
        viewer.input("home"); delay(60); assertEquals(0, sp.inputs)
        viewer.cancelSwitch(); until { source.state.value.switchState.isEmpty() }
        sp.frame(2); until { viewer.state.value.frame == 2L }
        viewer.requestSwitch(); until { source.state.value.switchState == "incoming" }
        viewer.cancelSwitch(); until { source.state.value.switchState.isEmpty() }
        assertEquals(0, vp.starts); assertEquals(1, sp.starts)
    }

    @Test fun `approved switch carries fresh frames audio and input both ways without reusing old callbacks`() = runBlocking(dispatcher) {
        val sp = Platform(); val vp = Platform(); val source = session(sp); val viewer = session(vp)
        connect(source, viewer, sp)
        sp.sound(); until { vp.played.size == 1 }; assertTrue(viewer.state.value.audioAvailable)
        val oldFrames = sp.frames!!; val oldAudio = sp.audio!!
        source.requestSwitch(); until { viewer.state.value.switchState == "incoming" }
        viewer.acceptSwitch(); until { viewer.state.value.stage == Stage.SHARING }
        assertNull(sp.frames); assertNull(sp.audio)
        oldFrames(Packet.Frame(99, 2, 2, byteArrayOf(1))); oldAudio(Packet.Audio(99, ByteArray(Wire.AUDIO_BYTES)))
        vp.frame(); until { source.state.value.stage == Stage.VIEWING }
        assertEquals(1, source.state.value.frame)
        vp.sound(); until { sp.played.size == 1 }; assertEquals(1, sp.played.first().epoch)
        source.input("tap", .5f, .5f); until { vp.inputs == 1 }
        source.requestSwitch(); until { viewer.state.value.switchState == "incoming" }
        viewer.acceptSwitch(); until { source.state.value.stage == Stage.SHARING }
        sp.frame(); until { viewer.state.value.stage == Stage.VIEWING }
        sp.sound(); until { vp.played.size == 2 }; assertEquals(2, vp.played.last().epoch)
        viewer.stop(); until { source.state.value.stage == Stage.ENDED }
        assertNull(sp.audio); assertNull(vp.audio)
    }

    @Test fun `muting drops playback while screen and session stay active`() = runBlocking(dispatcher) {
        val sp = Platform(); val vp = Platform(); val source = session(sp); val viewer = session(vp)
        connect(source, viewer, sp)
        sp.sound(); until { vp.played.size == 1 }
        viewer.toggleSound(); sp.sound(2); sp.frame(2); until { viewer.state.value.frame == 2L }
        delay(80); assertEquals(1, vp.played.size)
        viewer.toggleSound(); sp.sound(3); until { vp.played.size == 2 }
        assertEquals(Stage.VIEWING, viewer.state.value.stage)
    }

    @Test fun `switch permission denial ends access without capturing the newly requested screen`() = runBlocking(dispatcher) {
        val sp = Platform(); val vp = Platform(true).apply { controls = false; allowCapture = false }
        val source = session(sp); val viewer = session(vp)
        connect(source, viewer, sp)
        source.requestSwitch(); until { viewer.state.value.switchState == "incoming" }
        viewer.acceptSwitch(); until { viewer.state.value.stage == Stage.CONTROL_PERMISSION }
        assertEquals(0, vp.starts); assertNull(sp.frames); assertNull(sp.audio)
        viewer.shareWithoutControl(); until { source.state.value.stage == Stage.ENDED }
        assertNull(vp.frames); assertNull(vp.audio)
    }

    @Test fun `concurrent requests and disconnect cannot silently approve capture`() = runBlocking(dispatcher) {
        val sp = Platform(); val vp = Platform(); val source = session(sp); val viewer = session(vp)
        connect(source, viewer, sp)
        source.requestSwitch(); viewer.requestSwitch()
        until { source.state.value.switchState.isEmpty() && viewer.state.value.switchState.isEmpty() }
        assertEquals(0, vp.starts)
        source.requestSwitch(); until { viewer.state.value.switchState == "incoming" }
        source.stop(); until { viewer.state.value.stage == Stage.ENDED }
        viewer.acceptSwitch(); delay(50); assertEquals(0, vp.starts)
    }

    @Test fun `Android navigation can leave BetterDesk without operating approval or system settings`() {
        for (action in listOf("home", "back")) assertTrue(AndroidInputPolicy.allows("app.betterdesk", "app.betterdesk", action))
        for (action in listOf("tap", "text", "enter", "swipe")) assertFalse(AndroidInputPolicy.allows("app.betterdesk", "app.betterdesk", action))
        for (owner in listOf("com.android.settings", "com.android.systemui", "com.google.android.permissioncontroller", "")) {
            assertFalse(AndroidInputPolicy.allows(owner, "app.betterdesk", "tap"))
        }
        assertTrue(AndroidInputPolicy.allows("fixture.app", "app.betterdesk", "home"))
    }

    @Test fun `native PCM conversion preserves stereo packets and sample count across fragmented buffers`() {
        val packets = mutableListOf<Packet.Audio>()
        val converter = StereoPcm(48000, 2, 32, true, packets::add)
        val samples = java.nio.ByteBuffer.allocate(960 * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(960) { samples.putFloat(.5f); samples.putFloat(-.25f) }
        converter.accept(samples.array().copyOfRange(0, 400 * 8), 400)
        assertTrue(packets.isEmpty())
        converter.accept(samples.array().copyOfRange(400 * 8, 960 * 8), 560)
        assertEquals(1, packets.size); assertEquals(Wire.AUDIO_BYTES, packets.first().pcm.size)
        val decoded = java.nio.ByteBuffer.wrap(packets.first().pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(960) { assertEquals(16383, decoded.short.toInt()); assertEquals(-8191, decoded.short.toInt()) }
    }
}
