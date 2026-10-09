package app.betterdesk.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.agreement.jpake.*
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.*
import java.math.BigInteger
import java.net.*
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** LAN discovery carries no code, invitation or credential. J-PAKE authenticates the code first. */
object Pairing {
    const val DISCOVERY_PORT = 39471
    const val LIFETIME_MS = 5 * 60_000L
    private val random = SecureRandom()
    private val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it, "BetterDesk pairing deadline").apply { isDaemon = true } }
    internal fun deadline(socket: Socket) = deadlines.schedule({ runCatching { socket.close() }; Unit }, 20, TimeUnit.SECONDS)
    fun newCode(): String = random.nextInt(100_000_000).toString().padStart(8, '0')
    fun normalize(value: String): String = value.filterNot { it == ' ' || it == '-' }
    fun valid(value: String) = value.matches(Regex("[0-9]{8}"))
    fun validDeviceCode(value: String) = value.matches(Regex("[0-9]{8}|[0-9]{12}"))

    data class Endpoint(val host: String, val port: Int)

    fun discover(): List<Endpoint> {
        val nonce = UUID.randomUUID().toString()
        val query = "BetterDesk/2 discover $nonce".toByteArray()
        val targets = mutableSetOf("255.255.255.255", "127.0.0.1")
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.forEach { iface ->
            iface.interfaceAddresses.mapNotNull { it.broadcast?.hostAddress }.forEach(targets::add)
        }
        val found = linkedSetOf<Endpoint>()
        DatagramSocket().use { socket ->
            socket.broadcast = true; socket.soTimeout = 200
            repeat(2) {
                targets.forEach { address -> runCatching { socket.send(DatagramPacket(query, query.size, InetAddress.getByName(address), DISCOVERY_PORT)) } }
                val until = System.currentTimeMillis() + 650
                while (System.currentTimeMillis() < until && found.size < 16) {
                    val packet = DatagramPacket(ByteArray(256), 256)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    if (!packet.address.isSiteLocalAddress && !packet.address.isLoopbackAddress) continue
                    val parts = String(packet.data, 0, packet.length, Charsets.UTF_8).split(' ')
                    if (parts.size == 4 && parts[0] == "BetterDesk/2" && parts[1] == "device" && parts[2] == nonce) {
                        parts[3].toIntOrNull()?.takeIf { it in 1024..65535 }?.let { found.add(Endpoint(packet.address.hostAddress!!, it)) }
                    }
                }
            }
        }
        return found.toList()
    }

    fun send(endpoint: Endpoint, code: String, invitation: Invitation, onSocket: (Socket) -> Unit = {}) {
        require(valid(code))
        Socket().use { socket ->
            onSocket(socket)
            val timeout = deadline(socket)
            try {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 1200)
                socket.soTimeout = 5000; socket.tcpNoDelay = true
                val exchange = CodeExchange(socket, code, true)
                exchange.sendSecret(invitation.link())
                check(exchange.receiveSecret() == "accepted")
            } finally { timeout.cancel(false) }
        }
    }
}

class PairingHost(
    val code: String = Pairing.newCode(),
    private val bindAddress: InetAddress? = null,
    private val discovery: Boolean = true,
    private val onInvitation: (Invitation) -> Unit,
    private val onClosed: () -> Unit = {}
) : Closeable {
    val expires = System.currentTimeMillis() + Pairing.LIFETIME_MS
    private val closed = AtomicBoolean(false)
    private val listener = ServerSocket(0, 4, bindAddress).apply { soTimeout = 1000 }
    val port: Int get() = listener.localPort
    @Volatile private var datagram: DatagramSocket? = null
    @Volatile private var candidate: Socket? = null
    private val workers = Executors.newFixedThreadPool(2) { Thread(it, "BetterDesk pairing").apply { isDaemon = true } }

    fun start() {
        require(Pairing.valid(code))
        if (discovery) {
            // Bind synchronously: report a port conflict rather than displaying an unusable code.
            datagram = DatagramSocket(null).apply { bind(InetSocketAddress(bindAddress, Pairing.DISCOVERY_PORT)); soTimeout = 1000 }
            workers.execute {
                val udp = datagram ?: return@execute
                while (!closed.get()) {
                    try {
                        val request = DatagramPacket(ByteArray(256), 256); udp.receive(request)
                        if (!request.address.isSiteLocalAddress && !request.address.isLoopbackAddress) continue
                        val parts = String(request.data, 0, request.length, Charsets.UTF_8).split(' ')
                        if (parts.size != 3 || parts[0] != "BetterDesk/2" || parts[1] != "discover" || !parts[2].matches(Regex("[a-f0-9-]{36}"))) continue
                        val bytes = "BetterDesk/2 device ${parts[2]} $port".toByteArray()
                        udp.send(DatagramPacket(bytes, bytes.size, request.address, request.port))
                    } catch (_: SocketTimeoutException) { /* check close */ }
                    catch (_: IOException) { break }
                }
            }
        }
        workers.execute {
            var attempts = 0
            try {
                while (!closed.get() && System.currentTimeMillis() < expires && attempts < 12) {
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    candidate = socket
                    val timeout = Pairing.deadline(socket)
                    attempts++
                    val invitation = runCatching {
                        socket.use {
                            check(it.inetAddress.isSiteLocalAddress || it.inetAddress.isLoopbackAddress)
                            it.soTimeout = 5000; it.tcpNoDelay = true
                            val exchange = CodeExchange(it, code, false)
                            val value = Invitation.parse(exchange.receiveSecret())
                            check(System.currentTimeMillis() < expires && !closed.get())
                            exchange.sendSecret("accepted")
                            value
                        }
                    }.getOrNull()
                    timeout.cancel(false)
                    candidate = null
                    if (invitation != null && !closed.get()) { onInvitation(invitation); return@execute }
                    Thread.sleep(500)
                }
            } catch (_: IOException) { /* closed */ }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally { close(); onClosed() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }; runCatching { datagram?.close() }; runCatching { candidate?.close() }
        workers.shutdownNow()
    }
}

@Serializable private data class CodeRound(val id: String, val values: List<String>)

/** Uses BC's fixed NIST-3072 J-PAKE group, including mutual round-three key confirmation. */
internal class CodeExchange(socket: Socket, code: String, connector: Boolean) {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val localId = (if (connector) "connector:" else "device:") + UUID.randomUUID()
    private val key: ByteArray
    private val aad: ByteArray
    private val outgoing: Byte
    private val incoming: Byte
    private var sent = false
    private var received = false
    init {
        require(Pairing.valid(code))
        val password = code.toCharArray()
        val participant = JPAKEParticipant(localId, password)
        password.fill('\u0000')
        val one = participant.createRound1PayloadToSend()
        writeRound(CodeRound(localId, listOf(one.gx1, one.gx2, *one.knowledgeProofForX1, *one.knowledgeProofForX2).map { it.toString(16) }))
        val peerOne = readRound(6)
        require(peerOne.id.startsWith(if (connector) "device:" else "connector:"))
        val v = peerOne.values.map(::integer)
        participant.validateRound1PayloadReceived(JPAKERound1Payload(peerOne.id, v[0], v[1], arrayOf(v[2], v[3]), arrayOf(v[4], v[5])))
        val two = participant.createRound2PayloadToSend()
        writeRound(CodeRound(localId, listOf(two.a, *two.knowledgeProofForX2s).map { it.toString(16) }))
        val peerTwo = readRound(3); require(peerTwo.id == peerOne.id)
        val w = peerTwo.values.map(::integer)
        participant.validateRound2PayloadReceived(JPAKERound2Payload(peerTwo.id, w[0], arrayOf(w[1], w[2])))
        val material = participant.calculateKeyingMaterial()
        val three = participant.createRound3PayloadToSend(material)
        writeRound(CodeRound(localId, listOf(three.macTag.toString(16))))
        val peerThree = readRound(1); require(peerThree.id == peerOne.id)
        participant.validateRound3PayloadReceived(JPAKERound3Payload(peerThree.id, integer(peerThree.values[0])), material)
        val connectorId = if (connector) localId else peerOne.id
        val deviceId = if (connector) peerOne.id else localId
        aad = "BetterDesk/2 pairing|$connectorId|$deviceId".toByteArray()
        key = ByteArray(32).also { result ->
            HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(material.toByteArray(), null, aad)); generateBytes(result, 0, result.size) }
        }
        outgoing = if (connector) 1 else 2; incoming = if (connector) 2 else 1
    }
    private fun integer(value: String): BigInteger {
        require(value.length in 1..770 && value.matches(Regex("-?[0-9a-f]+")))
        return BigInteger(value, 16)
    }
    private fun writeRound(round: CodeRound) = write(Json.encodeToString(round).toByteArray())
    private fun readRound(count: Int): CodeRound = Json.decodeFromString<CodeRound>(read().toString(Charsets.UTF_8)).also {
        require(it.id.length in 40..46 && it.values.size == count)
    }
    private fun write(bytes: ByteArray) { require(bytes.size in 1..8192); output.writeInt(bytes.size); output.write(bytes); output.flush() }
    private fun read(): ByteArray { val size = input.readInt(); require(size in 1..8192); return ByteArray(size).also(input::readFully) }
    fun sendSecret(value: String) {
        check(!sent); sent = true
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad + outgoing)
        write(nonce + cipher.doFinal(value.toByteArray()))
    }
    fun receiveSecret(): String {
        check(!received); received = true
        val bytes = read(); require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad + incoming)
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
