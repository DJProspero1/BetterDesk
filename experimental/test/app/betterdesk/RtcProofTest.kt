package app.betterdesk

import app.betterdesk.core.*
import app.betterdesk.rtc.*
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.*
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.*

class RtcProofTest {
    @Test fun `native peers exchange binary using SDP authenticated by pinned TLS and invitation secret`() {
        authenticatedPeers(false)
    }

    @Test fun `authenticated native video tracks carry synthetic pixels and RTP in both directions`() {
        authenticatedPeers(true)
    }

    private fun authenticatedPeers(video: Boolean) {
        val identity = TlsIdentity.create()
        val secret = Invitation.secret()
        val session = Invitation.secret()
        val viewerId = "1".repeat(32)
        val sourceId = "2".repeat(32)
        val expires = System.currentTimeMillis() + 30_000
        val protocol = if (video) 2 else 1
        val serverPeer = DesktopRtcPeer(video)
        val clientPeer = DesktopRtcPeer(video)
        val executor = Executors.newSingleThreadExecutor()
        val listener = identity.context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.soTimeout = 15_000
        try {
            val server = executor.submit {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 15_000
                    val wire: SessionTransport = Wire(socket)
                    val offer = (wire.read() as Packet.Control).message
                    check(offer.type == "rtcProofOffer" && offer.protocol == protocol && offer.epoch == 1L)
                    check(TlsIdentity.constantEquals(secret, offer.secret))
                    val authenticated = RtcSignal.decode(offer.text).validate(session, viewerId, sourceId, 1, expires, true, video = video)
                    val answer = serverPeer.answer(authenticated).get(12, TimeUnit.SECONDS)
                    val signal = RtcSignal(session, sourceId, viewerId, 1, expires, false, RtcDescription.validate(answer, video), answer, protocol)
                    wire.send(Message("rtcProofAnswer", text = signal.encode(), protocol = protocol, epoch = 1))
                    serverPeer.opened.get(12, TimeUnit.SECONDS)
                    val incoming = serverPeer.receive(10_000)
                    assertContentEquals(ByteArray(RtcPeer.MAX_BYTES) { (it % 251).toByte() }, incoming)
                    serverPeer.send("Olá · synthetic".toByteArray()).get(5, TimeUnit.SECONDS)
                    if (video) {
                        repeat(3) { assertVideo(serverPeer.receiveVideo(5000), false); serverPeer.send(byteArrayOf(1)).get(2, TimeUnit.SECONDS) }
                        repeat(3) {
                            assertTrue(serverPeer.sendVideo(videoFrame(true)).get(2, TimeUnit.SECONDS))
                            assertContentEquals(byteArrayOf(2), serverPeer.receive(5000))
                        }
                        assertVideoCounters(serverPeer)
                    }
                    // Keep authenticated setup alive until the peer confirms delivery.
                    check((wire.read() as Packet.Control).message.type == "rtcProofDone")
                }
            }
            (TlsIdentity.client(identity.fingerprint).socketFactory.createSocket("127.0.0.1", listener.localPort) as SSLSocket).use { socket ->
                socket.soTimeout = 15_000
                val wire: SessionTransport = Wire(socket)
                val offer = clientPeer.offer().get(12, TimeUnit.SECONDS)
                assertEquals(64, RtcDescription.validate(offer, video).length)
                val signal = RtcSignal(session, viewerId, sourceId, 1, expires, true, RtcDescription.validate(offer, video), offer, protocol)
                wire.send(Message("rtcProofOffer", text = signal.encode(), secret = secret, protocol = protocol, epoch = 1))
                val answer = (wire.read() as Packet.Control).message
                check(answer.type == "rtcProofAnswer" && answer.protocol == protocol && answer.epoch == 1L)
                val authenticated = RtcSignal.decode(answer.text).validate(session, sourceId, viewerId, 1, expires, false, video = video)
                clientPeer.acceptAnswer(authenticated).get(12, TimeUnit.SECONDS)
                clientPeer.opened.get(12, TimeUnit.SECONDS)
                clientPeer.send(ByteArray(RtcPeer.MAX_BYTES) { (it % 251).toByte() }).get(5, TimeUnit.SECONDS)
                assertEquals("Olá · synthetic", clientPeer.receive(10_000).toString(Charsets.UTF_8))
                assertFailsWith<IllegalArgumentException> { clientPeer.send(ByteArray(RtcPeer.MAX_BYTES + 1)) }
                if (video) {
                    repeat(3) {
                        assertTrue(clientPeer.sendVideo(videoFrame(false)).get(2, TimeUnit.SECONDS))
                        assertContentEquals(byteArrayOf(1), clientPeer.receive(5000))
                    }
                    repeat(3) { assertVideo(clientPeer.receiveVideo(5000), true); clientPeer.send(byteArrayOf(2)).get(2, TimeUnit.SECONDS) }
                    assertVideoCounters(clientPeer)
                }
                wire.send(Message("rtcProofDone"))
            }
            server.get(5, TimeUnit.SECONDS)
        } finally {
            listener.close(); executor.shutdownNow()
            serverPeer.close(); clientPeer.close()
            assertTrue(serverPeer.awaitClosed(5000)); assertTrue(clientPeer.awaitClosed(5000))
        }
    }

    private fun videoFrame(reverse: Boolean): RtcVideoFrame {
        val width = 96; val height = 64
        val quarters = if (reverse) listOf(200, 160, 80, 40) else listOf(40, 80, 160, 200)
        val y = ByteArray(width * height) { offset -> quarters[(if (offset / width >= height / 2) 2 else 0) + (if (offset % width >= width / 2) 1 else 0)].toByte() }
        return RtcVideoFrame(width, height, y, ByteArray(y.size / 4) { 128.toByte() }, ByteArray(y.size / 4) { 128.toByte() })
    }
    private fun assertVideo(sample: RtcVideoSample, reverse: Boolean) {
        assertEquals(96, sample.width); assertEquals(64, sample.height); assertEquals(0, sample.rotation)
        val expected = if (reverse) listOf(200, 160, 80, 40) else listOf(40, 80, 160, 200)
        expected.zip(sample.quarters).forEach { (before, after) -> assertTrue(kotlin.math.abs(before - after) <= 12, "Synthetic video pixel mismatch") }
    }
    private fun assertVideoCounters(peer: DesktopRtcPeer) {
        val counters = peer.videoCounters().get(2, TimeUnit.SECONDS)
        assertTrue(counters.encoded >= 3 && counters.decoded >= 3)
        assertTrue(counters.sentBytes > 0 && counters.receivedBytes > 0)
    }

    @Test fun `Stop during video setup revokes raw frames and decoded callbacks`() {
        val peer = DesktopRtcPeer(true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit { peer.receiveVideo(30_000) }
            peer.offer(); peer.close()
            assertFailsWith<ExecutionException> { read.get(2, TimeUnit.SECONDS) }
            assertFailsWith<ExecutionException> { peer.sendVideo(videoFrame(false)).get(2, TimeUnit.SECONDS) }
            assertTrue(peer.awaitClosed(5000))
        } finally { peer.close(); executor.shutdownNow() }
    }

    @Test fun `raw video bridge validates planes and copies callers and native strides`() {
        val y = ByteArray(16) { 40 }; val uv = ByteArray(4) { 128.toByte() }
        val frame = RtcVideoFrame(4, 4, y, uv, uv)
        y.fill(99); uv.fill(99)
        val target = ByteBuffer.allocateDirect(32); val u = ByteBuffer.allocateDirect(8); val v = ByteBuffer.allocateDirect(8)
        frame.copyPlanes(target, 8, u, 4, v, 4)
        assertEquals(40, target.get(0).toInt()); assertEquals(40, target.get(27).toInt())
        assertEquals(128, u.get(0).toInt() and 255)
        assertFails { RtcVideoFrame(641, 360, ByteArray(0), uv, uv) }
        assertFails { RtcVideoFrame(4, 4, ByteArray(15), uv, uv) }
        assertFails { frame.copyPlanes(ByteBuffer.allocate(15), 4, u, 4, v, 4) }
        assertFalse(frame.toString().contains("40"))
    }

    @Test fun `video SDP and signaling cannot downgrade or substitute bundled identity`() {
        val fingerprint = "a=fingerprint:sha-256 " + List(32) { "AB" }.joinToString(":")
        val common = listOf(fingerprint, "a=ice-ufrag:synthetic", "a=ice-pwd:synthetic")
        val sdp = (listOf("v=0", "a=group:BUNDLE 0 1", "m=video 9 UDP/TLS/RTP/SAVPF 96", "a=mid:0", "a=rtcp-mux") + common +
            listOf("m=application 9 UDP/DTLS/SCTP webrtc-datachannel", "a=mid:1") + common).joinToString("\r\n") + "\r\n"
        assertEquals("ab".repeat(32), RtcDescription.validate(sdp, true))
        assertFails { RtcDescription.validate(sdp) }
        assertFails { RtcDescription.validate(sdp.replace("a=mid:1", "a=mid:0"), true) }
        assertFails { RtcDescription.validate(sdp.replace("UDP/TLS/RTP/SAVPF", "RTP/AVP"), true) }
        assertFails { RtcDescription.validate(sdp.replace("m=video", "m=audio"), true) }
        assertFails { RtcDescription.validate(sdp.replace("a=group:BUNDLE 0 1", "a=group:BUNDLE 0"), true) }
        assertFails { RtcDescription.validate(sdp.replaceFirst("AB:AB", "CD:CD"), true) }
        assertFails { RtcDescription.validate(sdp.replaceFirst("a=ice-pwd:synthetic", "a=ice-pwd:different"), true) }
        val split = sdp.indexOf("m=application")
        // Equal total counts must not hide an identity missing from one media section.
        val misplaced = sdp.substring(0, split).replace(fingerprint + "\r\n", "") + sdp.substring(split).replace(fingerprint, fingerprint + "\r\n" + fingerprint)
        assertFails { RtcDescription.validate(misplaced, true) }
        val now = 100_000L
        val signal = RtcSignal("A".repeat(43), "1".repeat(32), "2".repeat(32), 1, now + 20_000, true, "ab".repeat(32), sdp, 2)
        assertEquals(sdp, signal.validate(signal.session, signal.sender, signal.recipient, 1, signal.expires, true, now, video = true))
        assertFails { signal.validate(signal.session, signal.sender, signal.recipient, 1, signal.expires, true, now) }
        assertFails { signal.copy(protocol = 1).validate(signal.session, signal.sender, signal.recipient, 1, signal.expires, true, now, video = true) }
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
