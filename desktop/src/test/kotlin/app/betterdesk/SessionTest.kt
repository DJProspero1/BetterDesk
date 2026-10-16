package app.betterdesk

import androidx.compose.ui.graphics.ImageBitmap
import app.betterdesk.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "session-test").apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.add(error) } }
    }.asCoroutineDispatcher()
    private val sessions = mutableListOf<HelpSession>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        runBlocking(dispatcher) { sessions.forEach { it.dispose() } }
        Dispatchers.resetMain(); dispatcher.close()
        assertTrue(uncaught.isEmpty(), "Session failures must not escape to the Android main thread: $uncaught")
    }
    private class Platform(override val deviceIdentity: DeviceIdentity? = null, override val isAndroid: Boolean = false) : RemotePlatform {
        var failSaving = false
        private var storedDevices: String? = null
        override var rememberedDevices: String?
            get() = storedDevices
            set(value) { check(!failSaving); storedDevices = value }
        override val defaultName = "Test helper"
        var starts = 0; var inputs = 0
        var keptAlive = false
        @Volatile var responsive = true
        override fun captureResponsive() = responsive
        override fun keepConnectionAlive(active: Boolean) { keptAlive = active }
        var frame: ((Packet.Frame) -> Unit)? = null
        override fun controlAvailable() = true
        override fun openControlSettings() = Unit
        override suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit): Boolean { starts++; frame = onFrame; return true }
        override fun stopSharing() { frame = null }
        override fun applyInput(input: Message) { inputs++ }
        override fun decode(frame: Packet.Frame) = ImageBitmap(frame.width, frame.height)
    }
    private suspend fun until(condition: () -> Boolean) { withTimeout(12_000) { while (!condition()) delay(20) } }
    private fun session(platform: Platform) = HelpSession(platform, { listOf("127.0.0.1") }, java.net.InetAddress.getLoopbackAddress(), relayFactory = { null }).also(sessions::add)

    @Test fun `remember choice stays local before capture and signed code reconnect skips approval until removed`() = runBlocking(dispatcher) {
        val controller = session(Platform(testDeviceIdentity()))
        val platform = Platform(isAndroid = true)
        val target = session(platform)
        suspend fun request() {
            target.prepareDeviceCode()
            until { target.state.value.deviceCode.length == 8 }
            controller.connectCode(target.state.value.deviceCode)
        }
        request()
        until { target.state.value.stage == Stage.INVITATION }
        target.allowHelp()
        assertEquals(Stage.REMEMBER, target.state.value.stage)
        assertEquals(Stage.WAITING, controller.state.value.stage)
        assertEquals(0, platform.starts)
        target.finishRemember(true)
        until { target.state.value.stage == Stage.SHARING }
        assertTrue(target.state.value.remoteControl)
        assertEquals(1, RememberedDevices.read(platform.rememberedDevices).size)
        val key = target.remembered.value.single().key
        target.home(); controller.home()
        // A fresh session reloads the grant, as after rotation/restart/update.
        val restored = session(platform)
        restored.prepareDeviceCode()
        until { restored.state.value.deviceCode.length == 8 }
        controller.connectCode(restored.state.value.deviceCode)
        until { restored.state.value.stage == Stage.SHARING }
        assertEquals(2, platform.starts)
        restored.home(); controller.home()
        restored.forgetDevice(key)
        assertTrue(RememberedDevices.read(platform.rememberedDevices).isEmpty())
        restored.prepareDeviceCode()
        until { restored.state.value.deviceCode.length == 8 }
        controller.connectCode(restored.state.value.deviceCode)
        until { restored.state.value.stage == Stage.INVITATION }
        assertEquals(2, platform.starts)
        restored.allowHelp(); restored.finishRemember(false)
        until { restored.state.value.stage == Stage.SHARING }
        assertFalse(restored.state.value.remoteControl, "Stored accessibility alone must not authorize control")
        assertTrue(restored.remembered.value.isEmpty())
    }

    @Test fun `canceling remember starts no capture and a copied signed link cannot auto approve`() = runBlocking(dispatcher) {
        val controller = session(Platform(testDeviceIdentity()))
        val platform = Platform()
        val target = session(platform)
        target.prepareDeviceCode(); until { target.state.value.deviceCode.length == 8 }
        controller.connectCode(target.state.value.deviceCode)
        until { target.state.value.stage == Stage.INVITATION }
        val invite = target.state.value.invitation!!
        target.allowHelp(); assertEquals(Stage.REMEMBER, target.state.value.stage)
        target.home(); target.finishRemember(true)
        assertEquals(0, platform.starts); assertTrue(target.remembered.value.isEmpty())
        platform.rememberedDevices = RememberedDevices.encode(listOf(RememberedDevice(invite.deviceKey, invite.name)))
        val restored = session(platform)
        restored.acceptInvitation(invite.link())
        assertEquals(Stage.INVITATION, restored.state.value.stage)
        assertEquals(0, platform.starts)
    }

    @Test fun `failed persistence keeps remember private and just this time still works`() = runBlocking(dispatcher) {
        val controller = session(Platform(testDeviceIdentity()))
        val platform = Platform().apply { failSaving = true }
        val target = session(platform)
        target.prepareDeviceCode(); until { target.state.value.deviceCode.length == 8 }
        controller.connectCode(target.state.value.deviceCode)
        until { target.state.value.stage == Stage.INVITATION }
        target.allowHelp(); target.finishRemember(true)
        assertEquals(Stage.REMEMBER, target.state.value.stage)
        assertTrue(target.state.value.rememberError)
        assertEquals(Stage.WAITING, controller.state.value.stage)
        assertEquals(0, platform.starts)
        assertTrue(target.remembered.value.isEmpty())
        target.finishRemember(false)
        until { target.state.value.stage == Stage.SHARING }
        assertTrue(target.remembered.value.isEmpty())
    }

    @Test fun `canceling a local attempt never starts an Internet request`() = runBlocking(dispatcher) {
        val platform=Platform()
        val discovered=java.util.concurrent.atomic.AtomicBoolean(false)
        var relayRequests=0
        java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()).use { stalled ->
            val viewer=HelpSession(platform,{listOf("127.0.0.1")},java.net.InetAddress.getLoopbackAddress(),
                relayFactory={relayRequests++;null},discoverLocal={discovered.set(true);listOf(Pairing.Endpoint("127.0.0.1",stalled.localPort))}).also(sessions::add)
            viewer.connectCode("123456789012")
            until { discovered.get() };viewer.home();delay(250)
            assertEquals(Stage.HOME,viewer.state.value.stage)
            assertEquals(0,relayRequests);assertFalse(platform.keptAlive)
        }
    }

    @Test fun `unavailable Internet keeps a usable local code without granting capture`() = runBlocking(dispatcher) {
        val platform=Platform()
        val target=HelpSession(platform, { listOf("127.0.0.1") }, java.net.InetAddress.getLoopbackAddress(),
            relayFactory={ RelayClient("http://127.0.0.1:1", true) }).also(sessions::add)
        target.prepareDeviceCode()
        until { target.state.value.deviceCode.length==8 && !target.state.value.codeLoading }
        val viewer=session(Platform())
        viewer.connectCode(target.state.value.deviceCode)
        until { target.state.value.stage==Stage.INVITATION }
        assertFalse(target.state.value.viaRelay)
        assertEquals(0,platform.starts)
        target.stop();assertFalse(platform.keptAlive)
    }

    @Test fun `opening an invitation cannot capture until locally approved and stopping revokes control`() = runBlocking(dispatcher) {
        val hp = Platform(); val rp = Platform()
        val helper = session(hp); val recipient = session(rp)
        helper.createInvitation("Test helper")
        until { helper.state.value.stage == Stage.WAITING }
        val invite = helper.state.value.invitation!!
        recipient.acceptInvitation(invite.link())
        delay(100)
        assertEquals(0, rp.starts)
        assertEquals(Stage.INVITATION, recipient.state.value.stage)
        recipient.allowHelp()
        until { recipient.state.value.stage == Stage.SHARING }
        assertEquals(1, rp.starts)
        rp.frame?.invoke(Packet.Frame(1, 2, 2, byteArrayOf(1)))
        until { helper.state.value.stage == Stage.VIEWING }
        helper.input("tap", .5f, .5f)
        until { rp.inputs == 1 }
        recipient.stop()
        helper.input("tap", .5f, .5f)
        delay(100)
        assertEquals(1, rp.inputs)
        assertNull(rp.frame)
        assertEquals(Stage.ENDED, recipient.state.value.stage)
        until { helper.state.value.stage in setOf(Stage.ENDED, Stage.ERROR) }
        assertEquals(Stage.ENDED, helper.state.value.stage, helper.failureSummary)
    }

    @Test fun `canceling an invitation makes it unusable`() = runBlocking(dispatcher) {
        val helper = session(Platform())
        val rp = Platform(); val recipient = session(rp)
        helper.createInvitation("Helper")
        until { helper.state.value.stage == Stage.WAITING }
        val invite = helper.state.value.invitation!!
        helper.home()
        recipient.acceptInvitation(invite.link()); recipient.allowHelp()
        until { recipient.state.value.stage == Stage.ERROR }
        assertEquals(0, rp.starts)
    }

    @Test fun `unchanged healthy capture accepts input but a stalled capture does not`() = runBlocking(dispatcher) {
        val viewer = session(Platform())
        val sourcePlatform = Platform()
        val source = session(sourcePlatform)
        viewer.createInvitation("Viewer")
        until { viewer.state.value.stage == Stage.WAITING }
        source.acceptInvitation(viewer.state.value.invitation!!.link()); source.allowHelp()
        until { source.state.value.stage == Stage.SHARING }
        sourcePlatform.frame?.invoke(Packet.Frame(1, 2, 2, byteArrayOf(1)))
        until { viewer.state.value.stage == Stage.VIEWING }
        delay(3200) // No duplicate frames needed to keep an unchanged screen usable.
        assertEquals(1, viewer.state.value.frame)
        viewer.input("tap", .5f, .5f)
        until { sourcePlatform.inputs == 1 }
        sourcePlatform.responsive = false
        delay(3200)
        viewer.input("tap", .5f, .5f)
        delay(150)
        assertEquals(1, sourcePlatform.inputs)
    }

    @Test fun `entering a device code discovers it and still waits for local approval`() = runBlocking(dispatcher) {
        val source = session(Platform())
        val targetPlatform = Platform()
        val target = session(targetPlatform)
        val host = PairingHost("24681357", java.net.InetAddress.getLoopbackAddress(), true, {
            runBlocking(dispatcher) { target.acceptInvitation(it.link()) }
        })
        try {
            host.start()
            source.connectCode("2468 1357")
            until { target.state.value.stage == Stage.INVITATION }
            assertEquals(0, targetPlatform.starts)
            target.allowHelp()
            until { target.state.value.stage == Stage.SHARING }
            targetPlatform.frame?.invoke(Packet.Frame(1, 2, 2, byteArrayOf(1)))
            until { source.state.value.stage == Stage.VIEWING }
            target.stop()
            until { source.state.value.stage in setOf(Stage.ENDED, Stage.ERROR) }
            assertEquals(Stage.ENDED, source.state.value.stage, source.failureSummary)
        } finally { host.close() }
    }

    @Test fun `peer disconnect during continuous capture releases the source without crashing`() = runBlocking(dispatcher) {
        val viewer = session(Platform())
        val sourcePlatform = Platform()
        val source = session(sourcePlatform)
        viewer.createInvitation("Viewer")
        until { viewer.state.value.stage == Stage.WAITING }
        source.acceptInvitation(viewer.state.value.invitation!!.link()); source.allowHelp()
        until { source.state.value.stage == Stage.SHARING }
        val frames = launch {
            var id = 1L
            while (isActive) { sourcePlatform.frame?.invoke(Packet.Frame(id++, 2, 2, byteArrayOf(1))); delay(2) }
        }
        until { viewer.state.value.stage == Stage.VIEWING }
        viewer.stop()
        until { source.state.value.stage in setOf(Stage.ENDED, Stage.ERROR) }
        assertNull(sourcePlatform.frame)
        frames.cancelAndJoin()
    }

    @Test fun `abandoned approval releases background connection without capturing`() = runBlocking(dispatcher) {
        val platform = Platform()
        val target = session(platform)
        val invitation = Invitation(listOf("127.0.0.1"), 32123, "ab".repeat(32), Invitation.secret(), "Device", System.currentTimeMillis() + 300)
        target.acceptInvitation(invitation.link())
        assertTrue(platform.keptAlive)
        until { target.state.value.stage == Stage.ERROR }
        assertFalse(platform.keptAlive)
        assertEquals(0, platform.starts)
    }
}
