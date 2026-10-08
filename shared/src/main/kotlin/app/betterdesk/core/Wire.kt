package app.betterdesk.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.Closeable
import java.net.Socket

@Serializable
data class Message(
    val type: String, val text: String = "", val secret: String = "", val action: String = "",
    val x: Float = 0f, val y: Float = 0f, val endX: Float = 0f, val endY: Float = 0f,
    val frame: Long = 0, val control: Boolean = false
)

sealed interface Packet {
    data class Control(val message: Message) : Packet
    data class Frame(val id: Long, val width: Int, val height: Int, val jpeg: ByteArray) : Packet
}

class Wire(private val socket: Socket) : Closeable {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = false }

    fun send(message: Message) = synchronized(lock) {
        val bytes = json.encodeToString(message).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MESSAGE)
        output.writeByte(1); output.writeInt(bytes.size); output.write(bytes); output.flush()
    }

    fun sendFrame(frame: Packet.Frame) = synchronized(lock) {
        require(frame.jpeg.size in 1..MAX_FRAME && frame.width in 1..MAX_DIMENSION && frame.height in 1..MAX_DIMENSION)
        output.writeByte(2); output.writeInt(frame.jpeg.size); output.writeLong(frame.id)
        output.writeInt(frame.width); output.writeInt(frame.height); output.write(frame.jpeg); output.flush()
    }

    fun read(): Packet {
        val type = input.readUnsignedByte()
        val size = input.readInt()
        return when (type) {
            1 -> {
                require(size in 1..MAX_MESSAGE) { "Invalid message size" }
                val bytes = ByteArray(size).also(input::readFully)
                Packet.Control(json.decodeFromString<Message>(bytes.toString(Charsets.UTF_8)))
            }
            2 -> {
                require(size in 1..MAX_FRAME) { "Invalid frame size" }
                val id = input.readLong(); val width = input.readInt(); val height = input.readInt()
                require(id > 0 && width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION && width.toLong() * height <= 4_194_304)
                Packet.Frame(id, width, height, ByteArray(size).also(input::readFully))
            }
            else -> error("Unknown packet")
        }
    }

    override fun close() = socket.close()

    companion object { const val MAX_MESSAGE = 16_384; const val MAX_FRAME = 2_000_000; const val MAX_DIMENSION = 4096 }
}

/** Fail closed. These are the only remotely executable interactions. */
fun Message.isValidInput(): Boolean {
    if (type != "input" || frame <= 0 || text.length > 512) return false
    if (listOf(x, y, endX, endY).any { !it.isFinite() || it !in 0f..1f }) return false
    return action in setOf("tap", "right", "swipe", "scrollUp", "scrollDown", "text", "back", "home", "enter", "backspace")
}
