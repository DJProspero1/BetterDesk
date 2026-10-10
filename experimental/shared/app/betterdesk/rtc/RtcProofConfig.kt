package app.betterdesk.rtc

import kotlinx.serialization.Serializable

/** Private, short-lived fixture configuration; never source/build output or logs. */
@Serializable
data class RtcProofConfig(val port: Int, val pin: String, val secret: String,
    val session: String, val hostId: String, val androidId: String, val expires: Long, val video: Boolean = false) {
    override fun toString() = "RtcProofConfig(redacted)"
}
