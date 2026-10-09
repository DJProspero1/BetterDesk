package app.betterdesk

import app.betterdesk.core.*
import kotlin.test.*

class InvitationTest {
    private val now = System.currentTimeMillis()
    private fun sample() = Invitation(listOf("192.168.1.10", "10.0.0.2"), 41853, "ab".repeat(32), Invitation.secret(), "Luís & Paula", now + 60_000)
    @Test fun `names round trip without breaking query parameters`() { val value = sample(); assertEquals(value, Invitation.parse(value.link(), now)) }
    @Test fun `relay invitations round trip but cannot select an arbitrary endpoint`() {
        val relay = sample().copy(hosts=emptyList(), port=0, relayTicket=Invitation.secret())
        assertEquals(relay, Invitation.parse(relay.link(), now))
        assertFails { Invitation.parse(relay.link()+"&hosts=example.com&port=443",now) }
        assertFails { Invitation.parse(relay.copy(relayTicket="short").link(),now) }
        assertFails { Invitation.parse(relay.link()+"&ticket="+Invitation.secret(),now) }
    }
    @Test fun `Internet codes do not change the private pairing password length`() {
        assertTrue(Pairing.validDeviceCode("123456789012"))
        assertFalse(Pairing.valid("123456789012"))
        assertTrue(Pairing.valid("56789012"))
        assertFalse(Pairing.validDeviceCode("1234567890"))
        assertFails { RelayClient("http://api.betterdesk.dev") }
        assertFails { RelayClient("https://example.com") }
    }
    @Test fun `expired and far future invitations are rejected`() {
        assertFails { Invitation.parse(sample().copy(expires = now - 1).link(), now) }
        assertFails { Invitation.parse(sample().copy(expires = now + 900_000).link(), now) }
    }
    @Test fun `public addresses DNS names and ambiguous numeric hosts are rejected`() {
        listOf("8.8.8.8", "example.com", "127.1", "192.168.001.1", "169.254.169.254", "192.168.1.2@evil.com").forEach {
            assertFails(it) { Invitation.parse(sample().copy(hosts = listOf(it)).link(), now) }
        }
    }
    @Test fun `duplicate parameters invalid secret and unsupported version are rejected`() {
        assertFails { Invitation.parse(sample().link() + "&port=9999", now) }
        assertFails { Invitation.parse(sample().copy(secret = "123456").link(), now) }
        assertFails { Invitation.parse(sample().link().replace("v=1", "v=2"), now) }
    }
    @Test fun `input validation rejects NaN coordinates arbitrary commands and oversized text`() {
        assertFalse(Message("input", action = "tap", x = Float.NaN, frame = 1).isValidInput())
        assertFalse(Message("input", action = "shell", frame = 1).isValidInput())
        assertFalse(Message("input", action = "text", text = "x".repeat(513), frame = 1).isValidInput())
        assertFalse(Message("input", action = "tap", frame = 0).isValidInput())
        assertTrue(Message("input", action = "text", text = "Olá, ação!", frame = 1).isValidInput())
    }
}
