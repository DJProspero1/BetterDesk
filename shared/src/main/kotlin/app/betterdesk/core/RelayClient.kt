package app.betterdesk.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Serializable data class RelayConfig(val protocol: Int, val host: String, val port: Int, val ready: Boolean, val codeDigits: Int)
@Serializable data class RelayLease(val id: String, val token: String, val expires: Long)
@Serializable data class RelayRequest(val pairTicket: String, val viewerTicket: String, val deviceTicket: String, val expires: Long)
@Serializable data class RelayIncoming(val idle: Boolean = false, val ticket: String = "", val expires: Long = 0)
class RelayFailure(val status: Int) : IOException("Connection service unavailable")

/** HTTPS authenticates routing configuration. Invitations never select a remote hostname. */
class RelayClient internal constructor(private val base: String = "https://api.betterdesk.dev", private val loopbackTest: Boolean = false) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val closed = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    init {
        val url = URL(base)
        require(url.userInfo == null && url.query == null && url.ref == null)
        require(base == "https://api.betterdesk.dev" || (loopbackTest && url.protocol == "http" && url.host == "127.0.0.1"))
    }
    private fun request(path: String, method: String = "GET", body: JsonObject? = null, token: String? = null): String {
        check(!closed.get())
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connections.add(connection)
        try {
            check(!closed.get())
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 5000; connection.readTimeout = if (path.endsWith("/next")) 25000 else 8000
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw RelayFailure(status)
            require(connection.contentType?.substringBefore(';') == "application/json")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(1024)
                while (true) { val n = input.read(chunk); if (n < 0) break; require(out.size() + n <= 16384); out.write(chunk, 0, n) }
                out.toString("UTF-8")
            }
        } finally { connections.remove(connection); connection.disconnect() }
    }
    fun config(): RelayConfig = json.decodeFromString<RelayConfig>(request("/v1/config")).also {
        require(it.protocol == 1 && it.ready && it.codeDigits == 12 && it.port in 1..65535)
        require((loopbackTest && it.host == "127.0.0.1") || it.host.matches(Regex("[a-z0-9-]+\\.proxy\\.rlwy\\.net")))
    }
    fun register(): RelayLease = json.decodeFromString<RelayLease>(request("/v1/devices", "POST", buildJsonObject {})).also {
        require(it.id.matches(Regex("[0-9]{4}"))); ticket(it.token); expiry(it.expires)
    }
    fun next(lease: RelayLease): RelayIncoming = json.decodeFromString<RelayIncoming>(request("/v1/devices/${lease.id}/next", token = lease.token)).also {
        if (!it.idle) { ticket(it.ticket); expiry(it.expires) }
    }
    fun allocate(prefix: String): RelayRequest {
        require(prefix.matches(Regex("[0-9]{4}")))
        return json.decodeFromString<RelayRequest>(request("/v1/requests", "POST", buildJsonObject { put("id", prefix) })).also {
            ticket(it.pairTicket); ticket(it.viewerTicket); ticket(it.deviceTicket); expiry(it.expires)
        }
    }
    fun revoke(lease: RelayLease) { request("/v1/devices/${lease.id}", "DELETE", buildJsonObject {}, lease.token) }
    fun cancel(ticket: String) { ticket(ticket); request("/v1/requests", "DELETE", buildJsonObject { put("ticket", ticket) }) }
    fun active(ticket: String): Boolean = try {
        ticket(ticket); request("/v1/requests/status", "POST", buildJsonObject { put("ticket", ticket) }); true
    } catch (e: RelayFailure) { if (e.status == 410) false else throw e }
    fun connect(config: RelayConfig, ticket: String, expires: Long, onSocket: (Socket) -> Unit): Socket {
        ticket(ticket)
        val socket = Socket()
        try {
            onSocket(socket); check(!closed.get())
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(config.host, config.port), 6000)
            socket.soTimeout = (expires - System.currentTimeMillis()).coerceIn(1, Pairing.LIFETIME_MS).toInt()
            socket.getOutputStream().write((ticket + "\n").toByteArray(Charsets.US_ASCII))
            check(socket.getInputStream().read() == 1)
            socket.soTimeout = 8000
            return socket
        } catch (e: Exception) { socket.close(); throw e }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // HttpURLConnection.disconnect can wait for a read; never do it on the UI thread.
        cleanup.execute { connections.forEach { runCatching { it.disconnect() } }; connections.clear() }
    }
    internal fun cleanupLease(lease: RelayLease) { cleanup.execute { runCatching { RelayClient(base, loopbackTest).use { it.revoke(lease) } } } }
    fun cleanupRequest(ticket: String) { cleanup.execute { runCatching { RelayClient(base, loopbackTest).use { it.cancel(ticket) } } } }
    companion object {
        private val cleanup = Executors.newCachedThreadPool { Thread(it, "BetterDesk connection cleanup").apply { isDaemon = true } }
        private fun ticket(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{43}"))) }
        private fun expiry(value: Long) { require(value > System.currentTimeMillis() && value - System.currentTimeMillis() <= 10 * 60_000) }
    }
}
