package app.betterdesk

import app.betterdesk.core.Message
import app.betterdesk.core.Wire
import app.betterdesk.rtc.*
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.*
import kotlin.test.*

class RtcControlTest {
    @Test fun `bounded reassembly preserves Unicode across fragments and the exact JSON ceiling`() {
        val message = Message("exchangeText", text = "x".repeat(8000) + "Olá 😀".repeat(700))
        val fragments = RtcControlEnvelope.fragment(message, 7, 9)
        assertTrue(fragments.size > 1)
        val assembly = RtcControlEnvelope.Assembly(7, 9)
        fragments.dropLast(1).forEach { assertNull(assembly.accept(it)) }
        assertEquals(message, assembly.accept(fragments.last()))
        assertFails { assembly.accept(fragments.last()) }
        val empty = Message("exchangeText")
        val base = RtcControlEnvelope.fragment(empty, 1, 1).single().size - RtcControlEnvelope.HEADER
        val max = empty.copy(text = "x".repeat(Wire.MAX_MESSAGE - base))
        assertEquals(Wire.MAX_MESSAGE, RtcControlEnvelope.fragment(max, 1, 1).sumOf { it.size - RtcControlEnvelope.HEADER })
        assertFails { RtcControlEnvelope.fragment(max.copy(text = max.text + "x"), 1, 1) }
        assertFails { RtcControlEnvelope.fragment(empty.copy(text = "😀".repeat(5000)), 1, 1) }
        assertFails { RtcControlEnvelope.fragment(empty.copy(text = "x".repeat(Wire.MAX_MESSAGE + 1)), 1, 1) }
    }

    @Test fun `replay wrong generation reordering changed lengths and trailing bytes fail before JSON`() {
        val fragments = RtcControlEnvelope.fragment(Message("exchangeText", text = "x".repeat(9000)), 7, 9)
        fun rejected(change: (ByteArray) -> ByteArray) {
            assertFails { RtcControlEnvelope.Assembly(7, 9).accept(change(fragments.first().copyOf())) }
        }
        rejected { it.also { ByteBuffer.wrap(it).putInt(0, 0) } }
        rejected { it.also { ByteBuffer.wrap(it).putInt(4, 1) } }
        rejected { it.also { ByteBuffer.wrap(it).putLong(8, 8) } }
        rejected { it.also { ByteBuffer.wrap(it).putLong(16, 8) } }
        rejected { it.also { ByteBuffer.wrap(it).putInt(24, Int.MAX_VALUE) } }
        rejected { it.also { ByteBuffer.wrap(it).putInt(28, -1) } }
        rejected { it.copyOf(it.size + 1) }
        rejected { it.copyOf(it.size - 1) }
        assertFails { RtcControlEnvelope.Assembly(7, 9).accept(fragments.last()) }
        val assembly = RtcControlEnvelope.Assembly(7, 9)
        assertNull(assembly.accept(fragments.first()))
        assertFails { assembly.accept(fragments.first()) }
        val changed = fragments.last().copyOf().also { ByteBuffer.wrap(it).putInt(24, 10_000) }
        assertFails { assembly.accept(changed) }
    }

    @Test fun `invalid UTF8 schema and incomplete messages close with redacted errors`() {
        fun malformed(payload: ByteArray): ByteArray = ByteBuffer.allocate(RtcControlEnvelope.HEADER + payload.size)
            .putInt(0x42444331).putInt(0).putLong(1).putLong(1).putInt(payload.size).putInt(0).put(payload).array()
        val prefix = "{\"type\":\"exchangeText\",\"text\":\"synthetic-private-".toByteArray()
        val invalid = prefix + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        for (bytes in listOf(invalid, "{\"type\":\"x\",\"unknown\":\"synthetic-private\"}".toByteArray())) {
            val peer = TestPeer()
            val channel = RtcControlChannel(peer, 1)
            try {
                peer.deliver(malformed(bytes))
                val error = assertFailsWith<IOException> { channel.read(100) }
                assertEquals("RTC control channel closed", error.message)
                assertNull(error.cause)
                assertTrue(peer.awaitClosed(1000))
            } finally { channel.close() }
        }
        val peer = TestPeer(); val channel = RtcControlChannel(peer, 1)
        try {
            peer.deliver(RtcControlEnvelope.fragment(Message("exchangeText", text = "x".repeat(9000)), 1, 1).first())
            assertFailsWith<IOException> { channel.read(50) }
            assertTrue(peer.awaitClosed(1000))
        } finally { channel.close() }
    }

    @Test fun `Stop and interruption unblock framed reads reject writes and revoke callbacks`() {
        val peer = TestPeer(); val channel = RtcControlChannel(peer, 1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val read = executor.submit<Message> { entered.countDown(); channel.read() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            channel.close(); channel.close()
            peer.deliver(RtcControlEnvelope.fragment(Message("heartbeat"), 1, 1).single())
            assertFailsWith<ExecutionException> { read.get(1, TimeUnit.SECONDS) }
            assertFailsWith<IOException> { channel.send(Message("stop")) }
            assertFailsWith<IOException> { channel.read(100) }
            assertTrue(peer.awaitClosed(1000))
        } finally { channel.close(); executor.shutdownNow() }
        val interruptedPeer = TestPeer(); val interruptedChannel = RtcControlChannel(interruptedPeer, 1)
        val interrupted = CompletableFuture<Boolean>()
        val started = CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            try { interruptedChannel.read(); interrupted.complete(false) }
            catch (_: IOException) { interrupted.complete(Thread.currentThread().isInterrupted) }
        }
        try {
            thread.start(); assertTrue(started.await(1, TimeUnit.SECONDS)); thread.interrupt()
            assertTrue(interrupted.get(1, TimeUnit.SECONDS))
            assertTrue(interruptedPeer.awaitClosed(1000))
        } finally { interruptedChannel.close(); thread.interrupt(); thread.join(1000) }
    }

    private class TestPeer : RtcPeer() {
        init { opened.complete(Unit) }
        fun deliver(bytes: ByteArray) = incoming(ByteBuffer.wrap(bytes), true)
        override fun createDescription(offer: Boolean, result: (String?) -> Unit) = error("Unused")
        override fun setDescription(local: Boolean, offer: Boolean, sdp: String, result: (Boolean) -> Unit) = error("Unused")
        override fun gatheredDescription(): String? = null
        override fun gatheringComplete() = false
        override fun sendNative(bytes: ByteArray) = true
        override fun closeNative() = Unit
    }
}
