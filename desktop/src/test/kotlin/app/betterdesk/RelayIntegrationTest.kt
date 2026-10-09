package app.betterdesk

import androidx.compose.ui.graphics.ImageBitmap
import app.betterdesk.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Before
import org.junit.After
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Uses the actual Node broker. Public Railway checks are explicitly opt-in. No screen capture. */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayIntegrationTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val sessions = mutableListOf<HelpSession>()
    private var fixture: Process? = null
    private var base = "https://api.betterdesk.dev"
    private val live = System.getenv("BETTERDESK_RELAY_LIVE") == "1"
    @Before fun setup() {
        if (!live) {
            val script = System.getenv("BETTERDESK_BROKER_FIXTURE")?.let(::File)
            Assume.assumeTrue("Set BETTERDESK_BROKER_FIXTURE to the infrastructure broker fixture", script?.isFile == true)
            fixture = ProcessBuilder("node", script!!.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            base = Json.parseToJsonElement(fixture!!.inputStream.bufferedReader().readLine()).jsonObject.getValue("url").jsonPrimitive.content
        }
        Dispatchers.setMain(dispatcher)
    }
    @After fun cleanup() {
        runBlocking(dispatcher) { sessions.forEach(HelpSession::dispose) }
        Dispatchers.resetMain(); dispatcher.close()
        fixture?.let { it.outputStream.close(); if (!it.waitFor(3, TimeUnit.SECONDS)) it.destroyForcibly() }
    }
    private class Platform : RemotePlatform {
        override val isAndroid = false
        override val defaultName = "Transport test"
        var starts = 0; var inputs = 0
        var frame: ((Packet.Frame) -> Unit)? = null
        override fun controlAvailable() = true
        override fun openControlSettings() = Unit
        override suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit): Boolean { starts++; frame = onFrame; return true }
        override fun stopSharing() { frame = null }
        override fun applyInput(input: Message) { inputs++ }
        override fun decode(frame: Packet.Frame) = ImageBitmap(frame.width, frame.height)
    }
    private fun session(platform: Platform = Platform(), local: Boolean = false) = HelpSession(platform,
        { listOf("127.0.0.1") }, java.net.InetAddress.getLoopbackAddress(),
        relayFactory = { RelayClient(base, !live) }, localDiscovery = local).also(sessions::add)
    // Deliberately omit localDiscovery to exercise the app's real default.
    private fun defaultSession(platform: Platform = Platform(), bind: String = "127.0.0.1",
        discover: () -> List<Pairing.Endpoint> = Pairing::discover) = HelpSession(platform,
        { listOf(bind) }, java.net.InetAddress.getByName(bind),
        relayFactory = { RelayClient(base, !live) }, discoverLocal = discover).also(sessions::add)
    private suspend fun until(condition: () -> Boolean) { withTimeout(20000) { while (!condition()) delay(20) } }
    private suspend fun code(session: HelpSession): String {
        session.prepareDeviceCode(); until { session.state.value.deviceCode.length == 12 }
        return session.state.value.deviceCode
    }
    @Test fun `relay pairs with code and pins TLS before consented capture and control`() = runBlocking(dispatcher) {
        val platform = Platform(); val target = session(platform); val viewer = session()
        viewer.connectCode(code(target)); until { target.state.value.stage == Stage.INVITATION }
        assertEquals(0,platform.starts); assertTrue(target.state.value.viaRelay)
        target.allowHelp(); until { target.state.value.stage == Stage.SHARING }
        platform.frame?.invoke(Packet.Frame(1,2,2,byteArrayOf(1)))
        until { viewer.state.value.stage == Stage.VIEWING }; assertTrue(viewer.state.value.viaRelay)
        viewer.input("tap",.5f,.5f); until { platform.inputs == 1 }
        viewer.stop(); until { target.state.value.stage == Stage.ENDED }
        assertNull(platform.frame); assertEquals(1,platform.inputs)
    }
    @Test fun `wrong private digits cannot prompt or capture and correct retry works`() = runBlocking(dispatcher) {
        val platform = Platform(); val target = session(platform); val viewer = session()
        val original = code(target); val wrong = original.dropLast(1) + if(original.last()=='0') '1' else '0'
        viewer.connectCode(wrong); until { viewer.state.value.stage in setOf(Stage.ERROR,Stage.ENDED) }
        assertEquals(Stage.HOME,target.state.value.stage); assertEquals(0,platform.starts)
        viewer.home(); viewer.connectCode(original); until { target.state.value.stage == Stage.INVITATION }
        target.home(); until { viewer.state.value.stage in setOf(Stage.ERROR,Stage.ENDED) }
        assertEquals(0,platform.starts)
    }
    @Test fun `cancel clears the remote approval and cannot start capture`() = runBlocking(dispatcher) {
        val platform = Platform(); val target = session(platform); val viewer = session()
        viewer.connectCode(code(target)); until { target.state.value.stage == Stage.INVITATION }
        until { viewer.state.value.stage == Stage.WAITING }; viewer.stop()
        until { target.state.value.stage == Stage.ENDED }; target.allowHelp()
        assertEquals(0,platform.starts)
    }
    @Test fun `internet code uses a direct connection when the peer is nearby`() = runBlocking(dispatcher) {
        val platform = Platform(); val target = defaultSession(platform); val viewer = defaultSession()
        viewer.connectCode(code(target)); until { target.state.value.stage == Stage.INVITATION }
        assertFalse(target.state.value.viaRelay); target.allowHelp()
        until { target.state.value.stage == Stage.SHARING }
        platform.frame?.invoke(Packet.Frame(1,2,2,byteArrayOf(1)))
        until { viewer.state.value.stage == Stage.VIEWING }; assertFalse(viewer.state.value.viaRelay)
        target.stop(); until { viewer.state.value.stage == Stage.ENDED }
    }
    private suspend fun verifyFallback(viewer: HelpSession, target: HelpSession, platform: Platform) {
        viewer.connectCode(code(target)); until { target.state.value.stage == Stage.INVITATION }
        assertTrue(target.state.value.viaRelay); assertEquals(0,platform.starts)
        target.allowHelp(); until { target.state.value.stage == Stage.SHARING }
        platform.frame?.invoke(Packet.Frame(1,2,2,byteArrayOf(1)))
        until { viewer.state.value.stage == Stage.VIEWING }; assertTrue(viewer.state.value.viaRelay)
        viewer.input("tap",.5f,.5f); until { platform.inputs==1 }
        viewer.stop(); until { target.state.value.stage==Stage.ENDED }
    }
    @Test fun `default route falls back when no local peer answers`() = runBlocking(dispatcher) {
        val platform=Platform();val target=session(platform)
        verifyFallback(defaultSession(),target,platform)
    }
    @Test fun `discovery socket failure falls back without another user action`() = runBlocking(dispatcher) {
        val platform=Platform();val target=session(platform)
        verifyFallback(defaultSession(discover={throw java.net.SocketException("Discovery unavailable")}),target,platform)
    }
    @Test fun `discovered peer with a blocked reverse screen port falls back before approval`() = runBlocking(dispatcher) {
        val platform=Platform();val target=defaultSession(platform)
        // Pairing goes through 127.0.0.1; the screen listener is reachable only on 127.0.0.2.
        verifyFallback(defaultSession(bind="127.0.0.2"),target,platform)
    }
    @Test fun `unresponsive local candidate cannot delay relay by a full pairing timeout`() = runBlocking(dispatcher) {
        val platform=Platform();val target=session(platform)
        java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()).use { stalled ->
            val viewer=defaultSession(discover={listOf(Pairing.Endpoint("127.0.0.1",stalled.localPort))})
            val started=System.nanoTime()
            verifyFallback(viewer,target,platform)
            assertTrue((System.nanoTime()-started)/1_000_000 < 12000,"LAN fallback exceeded its bounded attempt")
        }
    }
    @Test fun `an external relay link cannot bypass authenticated code pairing`() = runBlocking(dispatcher) {
        val platform = Platform();val target = session(platform)
        val untrusted = Invitation(emptyList(),0,"ab".repeat(32),Invitation.secret(),"Unknown",System.currentTimeMillis()+60000,Invitation.secret())
        target.acceptInvitation(untrusted.link());assertEquals(Stage.ERROR,target.state.value.stage);assertEquals(0,platform.starts)
    }
}
