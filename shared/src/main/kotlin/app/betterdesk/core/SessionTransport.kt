package app.betterdesk.core

import java.io.Closeable
import java.net.InetAddress

/**
 * Authenticated packet transport, independent of capture and local approval.
 * Closing must unblock read and every writer. Handshake sockets remain owned by
 * HelpSession until they become a transport, so Stop can cancel setup as well.
 */
interface SessionTransport : Closeable {
    val peerAddress: InetAddress
    fun send(message: Message)
    fun sendFrame(frame: Packet.Frame)
    fun sendAudio(audio: Packet.Audio)
    fun read(): Packet
}
