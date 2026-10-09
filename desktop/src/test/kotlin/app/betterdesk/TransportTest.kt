package app.betterdesk

import app.betterdesk.core.*
import java.net.InetAddress
import java.net.Socket
import java.net.ServerSocket
import java.io.DataOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.*

class TransportTest {
    @Test fun `TLS session carries framed messages and images with certificate pinning`() {
        val identity = TlsIdentity.create()
        val executor = Executors.newSingleThreadExecutor()
        val listener = identity.context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.soTimeout = 5000
        try {
            val receiver = executor.submit<String> {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    val wire = Wire(socket)
                    val message = (wire.read() as Packet.Control).message
                    val frame = wire.read() as Packet.Frame
                    assertContentEquals(byteArrayOf(1, 2, 3), frame.jpeg)
                    assertEquals("jpeg", frame.codec)
                    val video = wire.read() as Packet.Frame
                    assertEquals("avc-intra", video.codec)
                    assertContentEquals(byteArrayOf(0, 0, 1, 5), video.jpeg)
                    val predictive = wire.read() as Packet.Frame
                    assertEquals("avc", predictive.codec)
                    assertFalse(predictive.keyFrame)
                    val audio = wire.read() as Packet.Audio
                    assertEquals(4, audio.epoch)
                    assertContentEquals(ByteArray(Wire.AUDIO_BYTES) { 4 }, audio.pcm)
                    wire.send(Message("accepted"))
                    message.text
                }
            }
            (TlsIdentity.client(identity.fingerprint).socketFactory.createSocket("127.0.0.1", listener.localPort) as SSLSocket).use { socket ->
                socket.soTimeout = 5000
                val wire = Wire(socket)
                wire.send(Message("hello", text = "Olá"))
                wire.sendFrame(Packet.Frame(1, 2, 2, byteArrayOf(1, 2, 3)))
                wire.sendFrame(Packet.Frame(2, 2, 2, byteArrayOf(0, 0, 1, 5), "avc-intra"))
                wire.sendFrame(Packet.Frame(3, 2, 2, byteArrayOf(0, 0, 1, 1), "avc", false))
                wire.sendAudio(Packet.Audio(1, ByteArray(Wire.AUDIO_BYTES) { 4 }, 4))
                assertEquals("accepted", (wire.read() as Packet.Control).message.type)
            }
            assertEquals("Olá", receiver.get(6, TimeUnit.SECONDS))
        } finally { listener.close(); executor.shutdownNow() }
    }

    @Test fun `different certificate is rejected before sending application data`() {
        val identity = TlsIdentity.create()
        val executor = Executors.newSingleThreadExecutor()
        val listener = identity.context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        try {
            val accept = executor.submit { runCatching { (listener.accept() as SSLSocket).use { it.soTimeout = 3000; it.startHandshake() } } }
            (TlsIdentity.client("00".repeat(32)).socketFactory.createSocket("127.0.0.1", listener.localPort) as SSLSocket).use { socket ->
                socket.soTimeout = 3000
                assertFails { socket.startHandshake() }
            }
            accept.get(5, TimeUnit.SECONDS)
        } finally { listener.close(); executor.shutdownNow() }
    }

    @Test fun `oversized network frames are rejected before allocation`() {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            Socket("127.0.0.1", listener.localPort).use { sender ->
                listener.accept().use { receiver ->
                    receiver.soTimeout = 3000
                    val out = DataOutputStream(sender.getOutputStream())
                    out.writeByte(2); out.writeInt(Int.MAX_VALUE); out.flush()
                    assertFails { Wire(receiver).read() }
                }
            }
        } finally { listener.close() }
    }

    @Test fun `invalid audio size is rejected before payload allocation`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { sender ->
                listener.accept().use { receiver ->
                    receiver.soTimeout = 1000
                    DataOutputStream(sender.getOutputStream()).apply { writeByte(5); writeInt(Int.MAX_VALUE); flush() }
                    assertFails { Wire(receiver).read() }
                }
            }
        }
    }
}
