package app.betterdesk

import androidx.test.platform.app.InstrumentationRegistry
import app.betterdesk.rtc.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import app.betterdesk.core.*
import kotlinx.serialization.json.Json
import javax.net.ssl.SSLSocket
import java.util.concurrent.TimeUnit

/** Synthetic host-only native peers; no real screen, audio or accessibility. */
class RtcAndroidProofTest {
    @Test fun nativeDesktopInteroperation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "rtc-proof.json")
        assumeTrue("Run through RtcAndroidHostTest", file.isFile)
        val config = try {
            require(file.length() in 1..4096)
            Json.decodeFromString<RtcProofConfig>(file.readText())
        } finally { file.delete() }
        require(config.port in 1..65535)
        require(config.pin.matches(Regex("[a-f0-9]{64}")))
        require(config.secret.matches(Regex("[A-Za-z0-9_-]{43}")))
        val peer = AndroidRtcPeer(context)
        try {
            (TlsIdentity.client(config.pin).socketFactory.createSocket("127.0.0.1", config.port) as SSLSocket).use { socket ->
                socket.soTimeout = 15_000
                val wire = Wire(socket)
                wire.send(Message("rtcProofHello", secret = config.secret, protocol = 1))
                val offer = (wire.read() as Packet.Control).message
                check(offer.type == "rtcProofOffer")
                val authenticated = RtcSignal.decode(offer.text).validate(config.session, config.hostId, config.androidId, 1, config.expires, true)
                val answer = peer.answer(authenticated).get(15, TimeUnit.SECONDS)
                val signal = RtcSignal(config.session, config.androidId, config.hostId, 1, config.expires, false, RtcDescription.validate(answer), answer)
                wire.send(Message("rtcProofAnswer", text = signal.encode()))
                peer.opened.get(15, TimeUnit.SECONDS)
                assertArrayEquals(ByteArray(RtcPeer.MAX_BYTES) { (it % 251).toByte() }, peer.receive(10_000))
                peer.send("Olá · synthetic Android".toByteArray()).get(5, TimeUnit.SECONDS)
                wire.send(Message("rtcProofDone"))
                check((wire.read() as Packet.Control).message.type == "rtcProofDone")
            }
        } finally { peer.close(); assertTrue(peer.awaitClosed(5000)) }
    }

    @Test fun nativeDataChannelAndTeardown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = AndroidRtcPeer(context)
        val second = AndroidRtcPeer(context)
        try {
            val offer = first.offer().get(15, TimeUnit.SECONDS)
            assertEquals(64, RtcDescription.validate(offer).length)
            val answer = second.answer(offer).get(15, TimeUnit.SECONDS)
            first.acceptAnswer(answer).get(15, TimeUnit.SECONDS)
            first.opened.get(15, TimeUnit.SECONDS); second.opened.get(15, TimeUnit.SECONDS)
            val payload = ByteArray(RtcPeer.MAX_BYTES) { (it % 251).toByte() }
            first.send(payload).get(5, TimeUnit.SECONDS)
            assertArrayEquals(payload, second.receive(10_000))
            second.send("Olá · synthetic".toByteArray()).get(5, TimeUnit.SECONDS)
            assertEquals("Olá · synthetic", first.receive(10_000).toString(Charsets.UTF_8))
        } finally {
            first.close(); second.close()
            assertTrue(first.awaitClosed(5000)); assertTrue(second.awaitClosed(5000))
        }
    }
}
