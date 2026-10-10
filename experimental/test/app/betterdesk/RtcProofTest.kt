package app.betterdesk

import app.betterdesk.core.*
import app.betterdesk.rtc.*
import java.net.InetAddress
import java.util.concurrent.*
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.*

class RtcProofTest {
    @Test fun `native peers exchange binary using SDP authenticated by pinned TLS and invitation secret`() {
        val identity = TlsIdentity.create()
        val secret = Invitation.secret()
        val session = Invitation.secret()
        val viewerId = "1".repeat(32)
        val sourceId = "2".repeat(32)
        val expires = System.currentTimeMillis() + 30_000
        val serverPeer = DesktopRtcPeer()
        val clientPeer = DesktopRtcPeer()
        val executor = Executors.newSingleThreadExecutor()
        val listener = identity.context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.soTimeout = 15_000
        try {
            val server = executor.submit {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 15_000
                    val wire: SessionTransport = Wire(socket)
                    val offer = (wire.read() as Packet.Control).message
                    check(offer.type == "rtcProofOffer" && offer.protocol == 1 && offer.epoch == 1L)
                    check(TlsIdentity.constantEquals(secret, offer.secret))
                    val authenticated = RtcSignal.decode(offer.text).validate(session, viewerId, sourceId, 1, expires, true)
                    val answer = serverPeer.answer(authenticated).get(12, TimeUnit.SECONDS)
                    val signal = RtcSignal(session, sourceId, viewerId, 1, expires, false, RtcDescription.validate(answer), answer)
                    wire.send(Message("rtcProofAnswer", text = signal.encode(), protocol = 1, epoch = 1))
                    serverPeer.opened.get(12, TimeUnit.SECONDS)
                    val incoming = serverPeer.receive(10_000)
                    assertContentEquals(ByteArray(RtcPeer.MAX_BYTES) { (it % 251).toByte() }, incoming)
                    serverPeer.send("Olá · synthetic".toByteArray()).get(5, TimeUnit.SECONDS)
                    // Keep authenticated setup alive until the peer confirms delivery.
                    check((wire.read() as Packet.Control).message.type == "rtcProofDone")
                }
            }
            (TlsIdentity.client(identity.fingerprint).socketFactory.createSocket("127.0.0.1", listener.localPort) as SSLSocket).use { socket ->
                socket.soTimeout = 15_000
                val wire: SessionTransport = Wire(socket)
                val offer = clientPeer.offer().get(12, TimeUnit.SECONDS)
                assertEquals(64, RtcDescription.validate(offer).length)
                val signal = RtcSignal(session, viewerId, sourceId, 1, expires, true, RtcDescription.validate(offer), offer)
                wire.send(Message("rtcProofOffer", text = signal.encode(), secret = secret, protocol = 1, epoch = 1))
                val answer = (wire.read() as Packet.Control).message
                check(answer.type == "rtcProofAnswer" && answer.protocol == 1 && answer.epoch == 1L)
                val authenticated = RtcSignal.decode(answer.text).validate(session, sourceId, viewerId, 1, expires, false)
                clientPeer.acceptAnswer(authenticated).get(12, TimeUnit.SECONDS)
                clientPeer.opened.get(12, TimeUnit.SECONDS)
                clientPeer.send(ByteArray(RtcPeer.MAX_BYTES) { (it % 251).toByte() }).get(5, TimeUnit.SECONDS)
                assertEquals("Olá · synthetic", clientPeer.receive(10_000).toString(Charsets.UTF_8))
                assertFailsWith<IllegalArgumentException> { clientPeer.send(ByteArray(RtcPeer.MAX_BYTES + 1)) }
                wire.send(Message("rtcProofDone"))
            }
            server.get(5, TimeUnit.SECONDS)
        } finally {
            listener.close(); executor.shutdownNow()
            serverPeer.close(); clientPeer.close()
            assertTrue(serverPeer.awaitClosed(5000)); assertTrue(clientPeer.awaitClosed(5000))
        }
    }

    @Test fun `Stop during native setup rejects later writes and unblocks a waiting read`() {
        val peer = DesktopRtcPeer()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit { peer.receive(30_000) }
            val offer = peer.offer()
            peer.close(); peer.close()
            assertFailsWith<ExecutionException> { read.get(2, TimeUnit.SECONDS) }
            assertFailsWith<ExecutionException> { peer.send(byteArrayOf(1)).get(2, TimeUnit.SECONDS) }
            assertTrue(offer.isDone)
            assertTrue(peer.awaitClosed(5000))
        } finally { peer.close(); executor.shutdownNow() }
    }

    @Test fun `duplicate native negotiation fails closed and tears down`() {
        val peer = DesktopRtcPeer()
        try {
            peer.offer().get(12, TimeUnit.SECONDS)
            assertFailsWith<ExecutionException> { peer.offer().get(2, TimeUnit.SECONDS) }
            assertFailsWith<ExecutionException> { peer.opened.get(2, TimeUnit.SECONDS) }
            assertTrue(peer.awaitClosed(5000))
        } finally { peer.close() }
    }

    @Test fun `malformed SDP never reaches native parsing`() {
        val valid = listOf("v=0", "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
            "a=fingerprint:sha-256 " + List(32) { "AB" }.joinToString(":"),
            "a=ice-ufrag:synthetic", "a=ice-pwd:synthetic").joinToString("\r\n") + "\r\n"
        assertEquals("ab".repeat(32), RtcDescription.validate(valid))
        assertFails { RtcDescription.validate(valid.replace("sha-256", "sha-1")) }
        assertFails { RtcDescription.validate(valid + "a=fingerprint:sha-256 " + List(32) { "AB" }.joinToString(":")) }
        assertFails { RtcDescription.validate(valid + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n") }
        assertFails { RtcDescription.validate(valid + "a=candidate:synthetic\r\n".repeat(33)) }
        assertFails { RtcDescription.validate("x".repeat(RtcDescription.MAX_BYTES + 1)) }
    }

    @Test fun `signaling binds identities expiry generation and DTLS fingerprint before native parsing`() {
        val sdp = listOf("v=0", "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
            "a=fingerprint:sha-256 " + List(32) { "AB" }.joinToString(":"),
            "a=ice-ufrag:synthetic", "a=ice-pwd:synthetic").joinToString("\r\n") + "\r\n"
        val now = 100_000L
        val signal = RtcSignal("A".repeat(43), "1".repeat(32), "2".repeat(32), 1, now + 20_000, true, "ab".repeat(32), sdp)
        fun validate(candidate: RtcSignal) = candidate.validate(signal.session, signal.sender, signal.recipient, 1, signal.expires, true, now)
        assertEquals(sdp, validate(RtcSignal.decode(signal.encode())))
        assertFails { validate(signal.copy(session = "B".repeat(43))) }
        assertFails { validate(signal.copy(sender = signal.recipient, recipient = signal.sender)) }
        assertFails { validate(signal.copy(generation = 2)) }
        assertFails { validate(signal.copy(expires = now - 1)) }
        assertFails { validate(signal.copy(fingerprint = "cd".repeat(32))) }
        assertFails { validate(signal.copy(protocol = 2)) }
        assertFails { validate(signal.copy(offer = false)) }
        assertFalse(signal.toString().contains(sdp))
    }
}
