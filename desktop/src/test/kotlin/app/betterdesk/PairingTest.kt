package app.betterdesk

import app.betterdesk.core.*
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PairingTest {
    private fun invitation() = Invitation(listOf("127.0.0.1"), 32123, "ab".repeat(32), Invitation.secret(), "Device", System.currentTimeMillis() + 60_000)

    @Test fun `short code securely transfers invitation and is single use`() {
        val received = CountDownLatch(1)
        val invite = invitation()
        var actual: Invitation? = null
        val host = PairingHost("12345678", InetAddress.getLoopbackAddress(), false, { actual = it; received.countDown() })
        try {
            host.start()
            val endpoint = Pairing.Endpoint("127.0.0.1", host.port)
            Pairing.send(endpoint, "12345678", invite)
            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertEquals(invite, actual)
            assertFails { Pairing.send(endpoint, "12345678", invite) }
        } finally { host.close() }
    }

    @Test fun `wrong code never delivers invitation and correct retry succeeds`() {
        val received = CountDownLatch(1)
        val host = PairingHost("12345678", InetAddress.getLoopbackAddress(), false, { received.countDown() })
        try {
            host.start()
            val endpoint = Pairing.Endpoint("127.0.0.1", host.port)
            assertFails { Pairing.send(endpoint, "87654321", invitation()) }
            assertEquals(1, received.count)
            Pairing.send(endpoint, "12345678", invitation())
            assertTrue(received.await(2, TimeUnit.SECONDS))
        } finally { host.close() }
    }

    @Test fun `canceled code cannot deliver an invitation`() {
        val host = PairingHost("12345678", InetAddress.getLoopbackAddress(), false, { fail("Canceled pairing accepted") })
        val endpoint = Pairing.Endpoint("127.0.0.1", host.port)
        host.start(); host.close()
        assertFails { Pairing.send(endpoint, "12345678", invitation()) }
    }

    @Test fun `slow viewer cannot accumulate old frames`() {
        val window = FrameWindow()
        assertTrue(window.offer(1)); assertTrue(window.offer(2))
        (3L..1000L).forEach { assertFalse(window.offer(it)) }
        window.acknowledge(999) // An invalid acknowledgement cannot free a slot.
        assertFalse(window.offer(1001))
        window.acknowledge(1)
        assertTrue(window.offer(1002))
        assertFalse(window.offer(1003))
        window.clear(); assertTrue(window.offer(1004))
    }
}
