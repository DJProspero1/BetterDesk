package app.betterdesk

import app.betterdesk.core.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Test
import kotlin.test.*

internal fun testDeviceIdentity(): DeviceIdentity {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    return object : DeviceIdentity {
        override val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
        override fun sign(bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
    }
}

class RememberedDevicesTest {
    private fun invitation() = Invitation(listOf("127.0.0.1"), 12345, "a".repeat(64), Invitation.secret(), "Synthetic device", System.currentTimeMillis() + 60_000)
    @Test fun `proof binds every invitation field and survives only local route observation`() {
        val identity = testDeviceIdentity()
        val original = invitation().signed(identity)
        assertEquals(original, Invitation.parse(original.link()))
        assertTrue(original.copy(observedHost = "127.0.0.2").verifiedDevice())
        listOf(original.copy(hosts = listOf("127.0.0.2")), original.copy(port = 12346), original.copy(fingerprint = "b".repeat(64)),
            original.copy(secret = Invitation.secret()), original.copy(name = "Another device"), original.copy(expires = original.expires + 1),
            original.copy(relayTicket = Invitation.secret()), original.copy(deviceKey = testDeviceIdentity().publicKey),
            original.copy(deviceProof = "a".repeat(96))).forEach { altered ->
            assertFalse(altered.verifiedDevice())
            assertFails { Invitation.parse(altered.link()) }
        }
        val relay = invitation().copy(hosts = emptyList(), port = 0, relayTicket = Invitation.secret()).signed(identity)
        assertEquals(relay, Invitation.parse(relay.link()))
        assertFails { Invitation.parse(original.copy(expires = System.currentTimeMillis() - 1).signed(identity).link()) }
    }
    @Test fun `local device names stay distinct and fit the stored name limit`() {
        val first = RememberedDevice(testDeviceIdentity().publicKey, "Office phone")
        val second = RememberedDevice(testDeviceIdentity().publicKey, "Office phone (2)")
        assertEquals("Office phone (3)", RememberedDevices.suggestName(listOf(first, second), "Office phone"))
        assertFalse(RememberedDevices.nameAvailable(listOf(first), "office PHONE"))
        assertFalse(RememberedDevices.nameAvailable(listOf(first), ""))
        assertFalse(RememberedDevices.nameAvailable(listOf(first), "bad\nname"))
        val long = "a".repeat(40)
        val suggested = RememberedDevices.suggestName(listOf(first.copy(name = long)), long)
        assertEquals(40, suggested.length)
        assertTrue(suggested.endsWith(" (2)"))
    }
    @Test fun `legacy invitations grant no identity and invalid stores fail closed`() {
        val legacy = Invitation.parse(invitation().link())
        assertFalse(legacy.verifiedDevice())
        assertTrue(RememberedDevices.read("malformed").isEmpty())
        val record = RememberedDevice(testDeviceIdentity().publicKey, "Synthetic device")
        assertEquals(listOf(record), RememberedDevices.read(RememberedDevices.encode(listOf(record))))
        assertTrue(RememberedDevices.read(RememberedDevices.encode(listOf(record, record))).isEmpty())
        assertTrue(RememberedDevices.read(RememberedDevices.encode(listOf(record.copy(key = "untrusted")))).isEmpty())
        assertTrue(RememberedDevices.read(RememberedDevices.encode((0..RememberedDevices.LIMIT).map { RememberedDevice(testDeviceIdentity().publicKey, "Synthetic device") })).isEmpty())
    }
}
