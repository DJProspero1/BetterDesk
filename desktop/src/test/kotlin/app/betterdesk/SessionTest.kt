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
    private class Platform : RemotePlatform {
        override val isAndroid = false
        override val defaultName = "Test helper"
        var starts = 0; var inputs = 0
        var keptAlive = false
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
    private fun session(platform: Platform) = HelpSession(platform, { listOf("127.0.0.1") }, java.net.InetAddress.getLoopbackAddress()).also(sessions::add)

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
        until { helper.state.value.stage == Stage.ENDED }
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
            until { source.state.value.stage == Stage.ENDED }
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
