package app.betterdesk

import androidx.compose.ui.graphics.ImageBitmap
import app.betterdesk.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real Android sockets, discovery, J-PAKE and TLS; synthetic capture/input only. */
class LocalRouteTest {
    @Test fun discoveryRepliesOverAndroidLocalInterface() {
        val local=java.net.InetAddress.getByName(Invitation.localAddresses().first())
        val host=PairingHost("12345678",null,true,{})
        try {
            host.start()
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout=3000
                val nonce=java.util.UUID.randomUUID().toString()
                val query="BetterDesk/2 discover $nonce".toByteArray()
                socket.send(java.net.DatagramPacket(query,query.size,local,Pairing.DISCOVERY_PORT))
                val reply=java.net.DatagramPacket(ByteArray(256),256)
                socket.receive(reply)
                assertEquals("BetterDesk/2 device $nonce ${host.port}",String(reply.data,0,reply.length))
            }
            assertTrue("Normal discovery did not receive its reply",Pairing.discover().isNotEmpty())
        } finally { host.close() }
    }
    private class Platform : RemotePlatform {
        override val isAndroid = true
        override val defaultName = "Local route test"
        var starts = 0
        var inputs = 0
        var frame: ((Packet.Frame) -> Unit)? = null
        override fun controlAvailable() = true
        override fun openControlSettings() = Unit
        override suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit): Boolean {
            starts++; frame=onFrame; return true
        }
        override fun stopSharing() { frame=null }
        override fun applyInput(input: Message) { inputs++ }
        override fun decode(frame: Packet.Frame) = ImageBitmap(frame.width,frame.height)
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(15000) {
        while(!condition()) delay(25)
    }
    @Test fun directConnectionUsesAndroidNetworkStackWithoutInternet() = runBlocking {
        val platform=Platform()
        var discoveries=0
        val target=HelpSession(platform,relayFactory={null})
        val viewer=HelpSession(Platform(),relayFactory={null},
            discoverLocal={Pairing.discover().also { discoveries=it.size }})
        try {
            withContext(Dispatchers.Main) { target.prepareDeviceCode() }
            until { target.state.value.deviceCode.length==8 }
            withContext(Dispatchers.Main) { viewer.connectCode(target.state.value.deviceCode) }
            until {
                check(viewer.state.value.stage !in setOf(Stage.ERROR,Stage.ENDED)) { "Local connection failed: ${viewer.state.value.error}; candidates=$discoveries; ${viewer.failureSummary}" }
                target.state.value.stage==Stage.INVITATION
            }
            assertFalse(target.state.value.viaRelay)
            assertEquals(0,platform.starts)
            withContext(Dispatchers.Main) { target.allowHelp() }
            until { target.state.value.stage==Stage.SHARING }
            platform.frame!!.invoke(Packet.Frame(1,2,2,byteArrayOf(1)))
            until { viewer.state.value.stage==Stage.VIEWING }
            assertFalse(viewer.state.value.viaRelay)
            withContext(Dispatchers.Main) { viewer.input("tap",.5f,.5f) }
            until { platform.inputs==1 }
            withContext(Dispatchers.Main) { viewer.stop() }
            until { target.state.value.stage==Stage.ENDED }
            assertNull(platform.frame)
        } finally {
            withContext(Dispatchers.Main) { viewer.dispose();target.dispose() }
        }
    }
}
