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
    val frame: Long = 0, val control: Boolean = false, val videoCodec: String = "jpeg",
    val epoch: Long = 0, val audio: Boolean = false, val protocol: Int = 0
)

sealed interface Packet {
    data class Control(val message: Message) : Packet
    data class Frame(val id: Long, val width: Int, val height: Int, val jpeg: ByteArray, val codec: String = "jpeg", val keyFrame: Boolean = true, val epoch: Long = 0) : Packet
    /** Exactly 20 ms of 48 kHz, stereo, signed 16-bit little-endian device playback. */
    data class Audio(val id: Long, val pcm: ByteArray, val epoch: Long = 0) : Packet
}

class Wire(private val socket: Socket) : Closeable {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun send(message: Message) = synchronized(lock) {
        val bytes = json.encodeToString(message).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MESSAGE)
        output.writeByte(1); output.writeInt(bytes.size); output.write(bytes); output.flush()
    }

    fun sendFrame(frame: Packet.Frame) = synchronized(lock) {
        require(frame.jpeg.size in 1..MAX_FRAME && frame.width in 1..MAX_DIMENSION && frame.height in 1..MAX_DIMENSION)
        require(frame.codec in setOf("jpeg", "avc-intra", "avc"))
        output.writeByte(when(frame.codec) { "avc" -> 4; "avc-intra" -> 3; else -> 2 }); output.writeInt(frame.jpeg.size); output.writeLong(frame.id)
        output.writeInt(frame.width); output.writeInt(frame.height); output.writeLong(frame.epoch)
        if (frame.codec == "avc") output.writeBoolean(frame.keyFrame)
        output.write(frame.jpeg); output.flush()
    }

    fun sendAudio(audio: Packet.Audio) = synchronized(lock) {
        require(audio.id > 0 && audio.epoch >= 0 && audio.pcm.size == AUDIO_BYTES)
        output.writeByte(5); output.writeInt(audio.pcm.size)
        output.writeLong(audio.id); output.writeLong(audio.epoch)
        output.write(audio.pcm); output.flush()
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
            2, 3, 4 -> {
                require(size in 1..MAX_FRAME) { "Invalid frame size" }
                val id = input.readLong(); val width = input.readInt(); val height = input.readInt()
                require(id > 0 && width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION && width.toLong() * height <= 4_194_304)
                val epoch = input.readLong(); require(epoch >= 0)
                val key = if (type == 4) input.readBoolean() else true
                Packet.Frame(id, width, height, ByteArray(size).also(input::readFully), when(type) { 4 -> "avc"; 3 -> "avc-intra"; else -> "jpeg" }, key, epoch)
            }
            5 -> {
                require(size == AUDIO_BYTES) { "Invalid audio size" }
                val id = input.readLong(); val epoch = input.readLong()
                require(id > 0 && epoch >= 0)
                Packet.Audio(id, ByteArray(size).also(input::readFully), epoch)
            }
            else -> error("Unknown packet")
        }
    }

    override fun close() = socket.close()

    companion object {
        const val MAX_MESSAGE = 16_384; const val MAX_FRAME = 2_000_000; const val MAX_DIMENSION = 4096
        const val AUDIO_RATE = 48_000; const val AUDIO_CHANNELS = 2; const val AUDIO_BYTES = 3840
    }
}

/** Fail closed. These are the only remotely executable interactions. */
fun Message.isValidInput(): Boolean {
    if (type != "input" || frame <= 0 || text.length > 512) return false
    if (listOf(x, y, endX, endY).any { !it.isFinite() || it !in 0f..1f }) return false
    return action in setOf("tap", "right", "swipe", "scrollUp", "scrollDown", "text", "back", "home", "enter", "backspace", "copy", "paste", "selectAll")
}
