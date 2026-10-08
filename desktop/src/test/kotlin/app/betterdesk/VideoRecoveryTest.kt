package app.betterdesk

import app.betterdesk.core.*
import org.junit.Test
import kotlin.test.*
import kotlinx.coroutines.*

class VideoRecoveryTest {
    private fun frame(id: Long, key: Boolean = false) = Packet.Frame(id, 64, 64, byteArrayOf(1), "avc", key)

    @Test fun `dropping a reference frame requires a later keyframe`() {
        var requests = 0
        val recovery = VideoRecovery { requests++ }
        assertFalse(recovery.accepts(frame(1)))
        assertTrue(recovery.accepts(frame(2, true)))
        assertTrue(recovery.accepts(frame(3)))
        recovery.discard(frame(4))
        assertFalse(recovery.accepts(frame(5)))
        assertFalse(recovery.accepts(frame(2, true)), "An older queued keyframe cannot heal a later gap")
        assertTrue(recovery.accepts(frame(6, true)))
        assertTrue(recovery.accepts(frame(7)))
        assertEquals(1, requests)
    }

    @Test fun `JPEG drops never interrupt independent frames`() {
        val recovery = VideoRecovery { fail("No video keyframe is needed") }
        recovery.discard(Packet.Frame(10, 2, 2, byteArrayOf(1)))
        assertTrue(recovery.accepts(Packet.Frame(11, 2, 2, byteArrayOf(1))))
    }

    @Test fun `short backpressure waits for acknowledgement and stalled peers time out`() = runBlocking {
        val window = FrameWindow(1)
        assertTrue(window.offer(1))
        val next = async { window.awaitOffer(2, 500) }
        delay(20)
        window.acknowledge(1)
        assertTrue(next.await())
        assertFalse(window.awaitOffer(3, 20))
        window.acknowledge(2)
        assertTrue(window.offer(4), "A timed-out offer must not occupy a slot")
    }
}
