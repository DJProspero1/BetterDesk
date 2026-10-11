package app.betterdesk.rtc

import app.betterdesk.core.Message
import app.betterdesk.core.Wire
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Development-only control framing. Owns its peer; callers must authenticate SDP first.
 * This transports messages, never authorizes input or capture, and carries no media/file bytes.
 * Use exactly one wrapper per peer and do not mix raw sends/receives with framed traffic.
 */
class RtcControlChannel(private val peer: RtcPeer, private val generation: Long) : Closeable {
    init { require(generation > 0) }
    private val closed = AtomicBoolean(false)
    private val writers = Semaphore(4)
    private val reader = Semaphore(1)
    private val writeLock = ReentrantLock(true)
    private var sent = 0L
    private var received = 0L

    /** Reliable ordered writes have four admitted callers, including the active writer. */
    fun send(message: Message) {
        if (!writers.tryAcquire()) fail()
        var locked = false
        try {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            locked = writeLock.tryLock(remaining(until), TimeUnit.MILLISECONDS)
            check(locked)
            checkOpen()
            check(sent < Long.MAX_VALUE)
            val fragments = RtcControlEnvelope.fragment(message, generation, sent + 1)
            fragments.forEach { peer.send(it).get(remaining(until), TimeUnit.MILLISECONDS) }
            checkOpen()
            sent++
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            fail()
        }
        finally { if (locked) writeLock.unlock(); writers.release() }
    }

    /** One bounded reassembly; the same deadline covers all fragments, never resets per fragment. */
    fun read(timeoutMillis: Long = 5000): Message {
        require(timeoutMillis in 1..5000)
        if (!reader.tryAcquire()) fail()
        try {
            checkOpen()
            check(received < Long.MAX_VALUE)
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val assembly = RtcControlEnvelope.Assembly(generation, received + 1)
            while (true) {
                val message = assembly.accept(peer.receive(remaining(until))) ?: continue
                checkOpen()
                received++
                return message
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            fail()
        }
        finally { reader.release() }
    }

    private fun checkOpen() { check(!closed.get()) }
    private fun remaining(until: Long): Long {
        val nanos = until - System.nanoTime()
        check(nanos > 0)
        return maxOf(1, TimeUnit.NANOSECONDS.toMillis(nanos))
    }
    private fun fail(): Nothing {
        close()
        // Serialization errors can contain clipboard/text content. Do not expose their cause.
        throw IOException("RTC control channel closed")
    }
    override fun close() { if (closed.compareAndSet(false, true)) peer.close() }
}

/** Exact ordered framing inside authenticated DTLS/SCTP; this is not an authentication scheme. */
internal object RtcControlEnvelope {
    private const val MAGIC = 0x42444331 // BDC1, version 1
    const val HEADER = 32
    const val CHUNK = 8192
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun fragment(message: Message, generation: Long, sequence: Long): List<ByteArray> {
        require(generation > 0 && sequence > 0)
        require(listOf(message.type, message.text, message.secret, message.action, message.videoCodec, message.key)
            .sumOf { it.length.toLong() } <= Wire.MAX_MESSAGE)
        val text = json.encodeToString(message)
        // Bound before UTF-8 conversion too. Messages exceeding the existing JSON ceiling fail.
        require(text.length in 1..Wire.MAX_MESSAGE)
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        require(encoded.remaining() in 1..Wire.MAX_MESSAGE)
        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
        return (bytes.indices step CHUNK).map { offset ->
            val count = minOf(CHUNK, bytes.size - offset)
            ByteBuffer.allocate(HEADER + count).putInt(MAGIC).putInt(0)
                .putLong(generation).putLong(sequence).putInt(bytes.size).putInt(offset)
                .put(bytes, offset, count).array()
        }
    }

    class Assembly(private val generation: Long, private val sequence: Long) {
        init { require(generation > 0 && sequence > 0) }
        private var bytes: ByteArray? = null
        private var position = 0
        private var complete = false
        fun accept(fragment: ByteArray): Message? {
            check(!complete)
            require(fragment.size in HEADER + 1..HEADER + CHUNK)
            val buffer = ByteBuffer.wrap(fragment)
            require(buffer.int == MAGIC && buffer.int == 0)
            require(buffer.long == generation && buffer.long == sequence)
            val total = buffer.int; val offset = buffer.int
            require(total in 1..Wire.MAX_MESSAGE && offset == position && offset < total)
            require(buffer.remaining() == minOf(CHUNK, total - offset))
            val target = bytes ?: ByteArray(total).also { bytes = it }
            require(target.size == total)
            buffer.get(target, position, buffer.remaining())
            position += fragment.size - HEADER
            if (position < total) return null
            complete = true
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(target)).toString()
            return json.decodeFromString<Message>(text)
        }
    }
}
