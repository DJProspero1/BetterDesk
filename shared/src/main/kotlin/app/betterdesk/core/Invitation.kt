package app.betterdesk.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections

data class Invitation(val hosts: List<String>, val port: Int, val fingerprint: String, val secret: String, val name: String, val expires: Long, val relayTicket: String? = null, val deviceKey: String = "", val deviceProof: String = "", val observedHost: String? = null) {
    fun link(): String {
        val fields = linkedMapOf("v" to if (deviceKey.isEmpty()) { if (relayTicket == null) "1" else "2" } else { if (relayTicket == null) "3" else "4" }, "key" to fingerprint,
            "secret" to secret, "name" to name, "expires" to expires.toString())
        if (relayTicket == null) { fields["hosts"] = hosts.joinToString(","); fields["port"] = port.toString() }
        else fields["ticket"] = relayTicket
        if (deviceKey.isNotEmpty()) { fields["device"] = deviceKey; fields["proof"] = deviceProof }
        return "betterdesk://help?" + fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    }

    private fun proofBytes() = ("BetterDesk remembered device v1\n" + copy(deviceKey = "", deviceProof = "").link()).toByteArray(Charsets.UTF_8)
    fun signed(identity: DeviceIdentity): Invitation = copy(deviceKey = identity.publicKey,
        deviceProof = Base64.getUrlEncoder().withoutPadding().encodeToString(identity.sign(proofBytes())))
    fun verifiedDevice(): Boolean = deviceKey.isNotEmpty() && RememberedDevices.verify(deviceKey, proofBytes(), deviceProof)

    companion object {
        fun parse(value: String, now: Long = System.currentTimeMillis()): Invitation {
            require(value.length <= 2048) { "Invalid invitation" }
            val uri = URI(value.trim())
            require(uri.scheme == "betterdesk" && uri.host == "help" && uri.userInfo == null && uri.fragment == null && uri.port == -1 && uri.path.isNullOrEmpty()) { "Invalid invitation" }
            val fields = uri.rawQuery.orEmpty().split("&").map {
                val pair = it.split("=", limit = 2)
                require(pair.size == 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            require(fields.size == fields.map { it.first }.distinct().size)
            val map = fields.toMap()
            val relay = map["v"] in setOf("2", "4")
            val signed = map["v"] in setOf("3", "4")
            val required = if (relay) setOf("v", "ticket", "key", "secret", "name", "expires") else setOf("v", "hosts", "port", "key", "secret", "name", "expires")
            require(map.keys == required + if (signed) setOf("device", "proof") else emptySet())
            require(map["v"] in setOf("1", "2", "3", "4"))
            val hosts = if (relay) emptyList() else map.getValue("hosts").split(",")
            val port = if (relay) 0 else map.getValue("port").toInt()
            if (!relay) {
                require(hosts.size in 1..8 && hosts.all(::isLocalAddress))
                require(port in 1024..65535)
            }
            val ticket = if (relay) map.getValue("ticket").also { require(it.matches(Regex("[A-Za-z0-9_-]{43}"))) } else null
            val fingerprint = map.getValue("key")
            require(fingerprint.matches(Regex("[a-f0-9]{64}")))
            val secret = map.getValue("secret")
            require(secret.matches(Regex("[A-Za-z0-9_-]{43}")))
            val name = map.getValue("name")
            require(name.isNotBlank() && name.length <= 40 && name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
            val expires = map.getValue("expires").toLong()
            require(expires > now && expires - now <= 10 * 60_000) { "Invitation expired" }
            return Invitation(hosts, port, fingerprint, secret, name, expires, ticket, map["device"].orEmpty(), map["proof"].orEmpty()).also {
                require(!signed || it.verifiedDevice()) { "Invalid device proof" }
            }
        }

        fun isLocalAddress(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
            val n = parts.map { it.toInt() }
            if (n.any { it !in 0..255 }) return false
            return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168) || n[0] == 127
        }

        fun localAddresses(): List<String> = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            .filter(::isLocalAddress).distinct().take(8)

        fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}
