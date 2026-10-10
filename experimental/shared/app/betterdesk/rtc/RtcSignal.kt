package app.betterdesk.rtc

import app.betterdesk.core.TlsIdentity
import app.betterdesk.core.Wire
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only carry this envelope inside an already authenticated channel. */
@Serializable
data class RtcSignal(
    val session: String, val sender: String, val recipient: String,
    val generation: Long, val expires: Long, val offer: Boolean,
    val fingerprint: String, val sdp: String, val protocol: Int = 1
) {
    override fun toString() = "RtcSignal(redacted)"

    fun encode(): String = json.encodeToString(this).also {
        require(it.toByteArray().size <= Wire.MAX_MESSAGE)
    }

    fun validate(expectedSession: String, expectedSender: String, expectedRecipient: String,
        expectedGeneration: Long, expectedExpiry: Long, expectedOffer: Boolean, now: Long = System.currentTimeMillis(), video: Boolean = false): String {
        require(protocol == (if (video) 2 else 1) && offer == expectedOffer)
        require(session.matches(Regex("[A-Za-z0-9_-]{43}")) && TlsIdentity.constantEquals(session, expectedSession))
        require(sender.matches(Regex("[a-f0-9]{32}")) && sender == expectedSender)
        require(recipient.matches(Regex("[a-f0-9]{32}")) && recipient == expectedRecipient && sender != recipient)
        require(generation == expectedGeneration && generation > 0)
        require(expires == expectedExpiry && expires > now && expires - now <= 30_000)
        require(fingerprint.matches(Regex("[a-f0-9]{64}")) && TlsIdentity.constantEquals(fingerprint, RtcDescription.validate(sdp, video)))
        return sdp
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
        fun decode(authenticatedText: String): RtcSignal {
            require(authenticatedText.toByteArray().size in 1..Wire.MAX_MESSAGE)
            return json.decodeFromString(authenticatedText)
        }
    }
}
