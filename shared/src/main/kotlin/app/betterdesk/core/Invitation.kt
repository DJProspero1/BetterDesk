package app.betterdesk.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections

data class Invitation(val hosts: List<String>, val port: Int, val fingerprint: String, val secret: String, val name: String, val expires: Long) {
    fun link(): String = "betterdesk://help?" + linkedMapOf(
        "v" to "1", "hosts" to hosts.joinToString(","), "port" to port.toString(), "key" to fingerprint,
        "secret" to secret, "name" to name, "expires" to expires.toString()
    ).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

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
            require(map.keys == setOf("v", "hosts", "port", "key", "secret", "name", "expires") && map["v"] == "1")
            val hosts = map.getValue("hosts").split(",")
            require(hosts.size in 1..8 && hosts.all(::isLocalAddress)) { "This version connects on a local network" }
            val port = map.getValue("port").toInt()
            require(port in 1024..65535)
            val fingerprint = map.getValue("key")
            require(fingerprint.matches(Regex("[a-f0-9]{64}")))
            val secret = map.getValue("secret")
            require(secret.matches(Regex("[A-Za-z0-9_-]{43}")))
            val name = map.getValue("name")
            require(name.isNotBlank() && name.length <= 40 && name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
            val expires = map.getValue("expires").toLong()
            require(expires > now && expires - now <= 10 * 60_000) { "Invitation expired" }
            return Invitation(hosts, port, fingerprint, secret, name, expires)
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
