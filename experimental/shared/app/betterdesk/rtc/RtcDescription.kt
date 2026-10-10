package app.betterdesk.rtc

/** Never log SDP: it contains IP addresses, ICE credentials and certificate identity. */
object RtcDescription {
    const val MAX_BYTES = 16_384
    fun validate(sdp: String): String {
        require(sdp.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES)
        val lines = sdp.split("\r\n")
        require(lines.size <= 256 && lines.first() == "v=0")
        require(lines.count { it.startsWith("m=") } == 1)
        require(lines.any { it.startsWith("m=application ") && "UDP/DTLS/SCTP" in it })
        val fingerprints = lines.filter { it.startsWith("a=fingerprint:") }
        require(fingerprints.size == 1)
        val fingerprint = fingerprints.single().removePrefix("a=fingerprint:sha-256 ")
        require(fingerprint.matches(Regex("(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}")))
        require(lines.count { it.startsWith("a=ice-ufrag:") } == 1)
        require(lines.count { it.startsWith("a=ice-pwd:") } == 1)
        require(lines.count { it.startsWith("a=candidate:") } <= 32)
        return fingerprint.replace(":", "").lowercase(java.util.Locale.ROOT)
    }
}
