package app.betterdesk.rtc

/** Never log SDP: it contains IP addresses, ICE credentials and certificate identity. */
object RtcDescription {
    const val MAX_BYTES = 16_384
    fun validate(sdp: String, video: Boolean = false): String {
        require(sdp.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES)
        val lines = sdp.split("\r\n")
        require(lines.size <= 256 && lines.first() == "v=0")
        require(lines.count { it.startsWith("m=") } == if (video) 2 else 1)
        require(lines.any { it.startsWith("m=application ") && "UDP/DTLS/SCTP" in it })
        if (video) {
            require(lines.count { it.startsWith("m=application ") } == 1)
            require(lines.count { it.startsWith("m=video ") && " UDP/TLS/RTP/SAVPF " in it } == 1)
            val starts = lines.indices.filter { lines[it].startsWith("m=") }
            val sections = starts.mapIndexed { index, start -> lines.subList(start, starts.getOrElse(index + 1) { lines.size }) }
            require(sections.single { it.first().startsWith("m=video ") }.contains("a=rtcp-mux"))
            for (prefix in listOf("a=fingerprint:", "a=ice-ufrag:", "a=ice-pwd:")) {
                require(sections.all { section -> section.count { it.startsWith(prefix) } == 1 })
            }
            val mids = sections.map { section -> section.single { it.startsWith("a=mid:") }.removePrefix("a=mid:") }
            require(mids.distinct().size == 2 && mids.all { it.matches(Regex("[A-Za-z0-9_-]{1,16}")) })
            val bundle = lines.single { it.startsWith("a=group:BUNDLE ") }.removePrefix("a=group:BUNDLE ").split(" ")
            require(bundle.size == 2 && bundle.toSet() == mids.toSet())
        }
        val fingerprints = lines.filter { it.startsWith("a=fingerprint:") }
        require(fingerprints.size == if (video) 2 else 1)
        require(fingerprints.distinct().size == 1)
        val fingerprint = fingerprints.first().removePrefix("a=fingerprint:sha-256 ")
        require(fingerprint.matches(Regex("(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}")))
        for (prefix in listOf("a=ice-ufrag:", "a=ice-pwd:")) {
            val values = lines.filter { it.startsWith(prefix) }
            require(values.size == if (video) 2 else 1)
            require(values.distinct().size == 1 && values.first().removePrefix(prefix).length in 1..256)
        }
        require(lines.count { it.startsWith("a=candidate:") } <= 32)
        return fingerprint.replace(":", "").lowercase(java.util.Locale.ROOT)
    }
}
