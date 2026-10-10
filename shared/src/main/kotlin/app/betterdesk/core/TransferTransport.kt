package app.betterdesk.core

import java.io.*
import java.net.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** One bounded, separately encrypted file batch. Never writes to the media socket. */
internal class TransferTransport(private val relayFactory: () -> RelayClient?, private val peerAddress: InetAddress,
    private val viaRelay: Boolean, private val congested: () -> Boolean) : Closeable {
    private val closed = AtomicBoolean(false)
    private val resources = ConcurrentHashMap.newKeySet<Closeable>()
    private fun <T : Closeable> own(resource: T): T {
        resources.add(resource)
        if (closed.get()) { resource.close(); throw InterruptedIOException() }
        return resource
    }
    private fun checkOpen() { if (closed.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException() }
    private fun tls(socket: SSLSocket): SSLSocket = own(socket).apply {
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        tcpNoDelay = true; soTimeout = 30_000
    }

    fun receive(offer: FileOffer, target: File, publish: (TransferLink) -> Unit, progress: (Long, Boolean) -> Unit) {
        offer.validate()
        val identity = TlsIdentity.create()
        val secret = Invitation.secret()
        val expires = System.currentTimeMillis() + 45_000
        var revoke: (() -> Unit)? = null
        try {
            val connection = if (viaRelay) {
                val client = own(relayFactory() ?: error("Network unavailable"))
                val config = client.config()
                val lease = client.register()
                val allocated = try { client.allocate(lease.id) } finally { client.cleanupLease(lease) }
                // Device cleanup only removes the unused pairing room, not the stream room.
                revoke = { client.cleanupRequest(allocated.viewerTicket) }
                publish(TransferLink(offer.id, identity.fingerprint, secret, ticket = allocated.deviceTicket, expires = expires))
                val raw = own(client.connect(config, allocated.viewerTicket, expires, { own(it) }))
                tls(identity.context.socketFactory.createSocket(raw, config.host, config.port, true) as SSLSocket).apply { useClientMode = false; startHandshake() }
            } else {
                val listener = own(identity.context.serverSocketFactory.createServerSocket(0) as SSLServerSocket)
                listener.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2").filter { it in listener.supportedProtocols }.toTypedArray()
                listener.soTimeout = 1000
                publish(TransferLink(offer.id, identity.fingerprint, secret, port = listener.localPort, expires = expires))
                var accepted: SSLSocket? = null
                var attempts = 0
                while (accepted == null && System.currentTimeMillis() < expires && attempts < 8) {
                    checkOpen()
                    val candidate = try { listener.accept() as SSLSocket } catch (_: SocketTimeoutException) { continue }
                    // A same-LAN third party cannot turn a valid session into a file destination.
                    if (candidate.inetAddress != peerAddress) { candidate.close(); attempts++; continue }
                    try { tls(candidate); candidate.soTimeout = 5000; candidate.startHandshake(); accepted = candidate }
                    catch (_: Exception) { candidate.close(); attempts++ }
                }
                listener.close()
                accepted ?: throw SocketTimeoutException()
            }
            connection.soTimeout = 30_000
            val input = DataInputStream(connection.inputStream.buffered(CHUNK))
            val output = DataOutputStream(connection.outputStream.buffered(CHUNK))
            require(input.readInt() == VERSION && input.readUTF() == offer.id && TlsIdentity.constantEquals(input.readUTF(), secret))
            output.writeBoolean(true); output.flush()
            var total = 0L
            val written = ArrayList<File>()
            for ((index, entry) in offer.entries.withIndex()) {
                checkOpen()
                val final = checkedFile(target, entry.path)
                if (entry.directory) { check(final.mkdirs() || final.isDirectory); continue }
                if (final.exists()) require(written.none { Files.isSameFile(it.toPath(), final.toPath()) })
                val parent = requireNotNull(final.parentFile)
                check(parent.mkdirs() || parent.isDirectory)
                // Numeric partial names cannot collide with sender-provided filenames.
                val partial = File(target.parentFile, "${target.name}-$index.partial")
                if (!partial.exists() && final.isFile) Files.move(final.toPath(), partial.toPath())
                if (partial.length() > entry.size) check(partial.delete())
                val offset = if (partial.isFile) partial.length() else 0L
                val digest = MessageDigest.getInstance("SHA-256")
                if (offset > 0) own(partial.inputStream()).use { prefix -> hashPrefix(prefix, offset, digest) }
                output.writeInt(index); output.writeLong(offset); output.flush()
                var received = offset
                progress(total + received, false)
                own(FileOutputStream(partial, true)).use { file ->
                    var chunks = 0
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        checkOpen()
                        val size = input.readInt()
                        if (size == 0) break
                        require(size in 1..CHUNK && received + size <= entry.size)
                        input.readFully(buffer, 0, size); file.write(buffer, 0, size); digest.update(buffer, 0, size)
                        received += size; progress(total + received, false)
                        if (++chunks % WINDOW == 0) {
                            output.writeLong(received); output.writeBoolean(congested()); output.flush()
                        }
                    }
                    require(received == entry.size)
                    progress(total + received, true)
                    val expected = ByteArray(32).also(input::readFully)
                    if (!MessageDigest.isEqual(expected, digest.digest())) {
                        file.close(); partial.delete(); output.writeBoolean(false); output.flush(); error("Integrity check failed")
                    }
                    file.fd.sync()
                }
                Files.move(partial.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
                written += final
                output.writeBoolean(true); output.flush()
                total += entry.size
            }
            output.writeBoolean(true); output.flush()
        } finally { revoke?.invoke(); close() }
    }

    fun send(offer: FileOffer, files: List<LocalFile>, link: TransferLink, progress: (Long, Boolean) -> Unit) {
        require(link.id == offer.id && link.fingerprint.matches(Regex("[a-f0-9]{64}")) && link.secret.matches(Regex("[A-Za-z0-9_-]{43}")))
        require(link.expires > System.currentTimeMillis() && link.expires - System.currentTimeMillis() <= 60_000)
        val context = TlsIdentity.client(link.fingerprint)
        try {
            val connection = if (viaRelay) {
                require(link.port == 0 && link.ticket.matches(Regex("[A-Za-z0-9_-]{43}")))
                val client = own(relayFactory() ?: error("Network unavailable"))
                val config = client.config()
                val raw = own(client.connect(config, link.ticket, link.expires, { own(it) }))
                tls(context.socketFactory.createSocket(raw, config.host, config.port, true) as SSLSocket)
            } else {
                require(link.ticket.isEmpty() && link.port in 1..65535 && (peerAddress.isSiteLocalAddress || peerAddress.isLoopbackAddress))
                tls(context.socketFactory.createSocket() as SSLSocket).apply { connect(InetSocketAddress(peerAddress, link.port), 5000) }
            }
            connection.startHandshake()
            val input = DataInputStream(connection.inputStream.buffered(CHUNK))
            val output = DataOutputStream(connection.outputStream.buffered(CHUNK))
            output.writeInt(VERSION); output.writeUTF(offer.id); output.writeUTF(link.secret); output.flush()
            check(input.readBoolean())
            var total = 0L
            var delayMs = 0L
            var minimumRtt = Long.MAX_VALUE
            var nextSendAt = System.nanoTime()
            for ((index, source) in files.withIndex()) {
                val entry = source.entry
                if (entry.directory) continue
                require(input.readInt() == index)
                val offset = input.readLong(); require(offset in 0..entry.size)
                val digest = MessageDigest.getInstance("SHA-256")
                own(source.open()).use { file ->
                    hashPrefix(file, offset, digest)
                    var sent = offset
                    var chunks = 0
                    val buffer = ByteArray(CHUNK)
                    while (sent < entry.size) {
                        checkOpen()
                        val n = file.read(buffer, 0, minOf(CHUNK.toLong(), entry.size - sent).toInt())
                        require(n > 0)
                        if (viaRelay) {
                            nextSendAt = maxOf(nextSendAt, System.nanoTime()) + n * 1_000_000_000L / (4 * 1024 * 1024)
                            val waitNanos = nextSendAt - System.nanoTime()
                            if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
                        }
                        digest.update(buffer, 0, n)
                        output.writeInt(n); output.write(buffer, 0, n)
                        sent += n
                        if (++chunks % WINDOW == 0) {
                            output.flush()
                            val ackStarted = System.nanoTime()
                            require(input.readLong() == sent)
                            val slowScreen = input.readBoolean() || congested()
                            val rtt = (System.nanoTime() - ackStarted) / 1_000_000
                            minimumRtt = minOf(minimumRtt, rtt)
                            delayMs = if (slowScreen || rtt > minimumRtt + 50) (delayMs + 10).coerceAtMost(150) else (delayMs - 2).coerceAtLeast(0)
                            // Stay comfortably below the existing relay's per-room burst ceiling.
                            val pause = delayMs
                            if (pause > 0) Thread.sleep(pause)
                        }
                        progress(total + sent, false)
                    }
                    // Reject a changed source rather than silently truncating it.
                    require(file.read() == -1)
                    output.writeInt(0); output.write(digest.digest()); output.flush()
                    progress(total + sent, true)
                    check(input.readBoolean())
                    total += sent
                }
            }
            check(input.readBoolean())
        } finally { close() }
    }

    private fun hashPrefix(input: InputStream, size: Long, digest: MessageDigest) {
        var remaining = size
        val buffer = ByteArray(CHUNK)
        while (remaining > 0) {
            checkOpen()
            val n = input.read(buffer, 0, minOf(remaining, CHUNK.toLong()).toInt()); require(n > 0)
            digest.update(buffer, 0, n); remaining -= n
        }
    }
    override fun close() { if (closed.compareAndSet(false, true)) resources.forEach { runCatching { it.close() } } }
    companion object {
        private const val VERSION = 1
        private const val CHUNK = 65_536
        private const val WINDOW = 8
        fun checkedFile(root: File, path: String): File {
            require(FileOffer.validPath(path))
            val file = File(root, path)
            require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
            return file
        }
    }
}
