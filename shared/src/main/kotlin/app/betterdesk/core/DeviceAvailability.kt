package app.betterdesk.core

import java.io.Closeable
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One displayed code; nearby discovery and the internet share its private eight digits. */
class DeviceAvailability(
    private val relay: RelayClient?, private val bindAddress: InetAddress?, private val localDiscovery: Boolean,
    private val onInvitation: (Invitation) -> Unit, private val onClosed: () -> Unit
) : Closeable {
    private val secret = Pairing.newCode()
    private val closed = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "BetterDesk availability").apply { isDaemon = true } }
    @Volatile private var local: PairingHost? = null
    @Volatile private var lease: RelayLease? = null
    @Volatile private var candidate: Socket? = null
    @Volatile private var starting = true
    var code = ""; private set
    var expires = 0L; private set
    val online get() = lease != null

    fun start() {
        try {
            if (localDiscovery) {
                val host = PairingHost(secret, bindAddress, true, ::deliver, {
                    if (!starting && lease == null) finish()
                })
                local = host
                runCatching { host.start() }.onFailure { host.close(); local = null }
            }
            val config = runCatching {
                relay?.config()?.also {
                    val registered = relay.register()
                    lease = registered
                    if (closed.get()) { relay.cleanupLease(registered); error("Canceled") }
                }
            }.getOrNull()
            check(!closed.get())
            val registration = lease
            check(registration != null || local != null)
            code = registration?.let { it.id + secret } ?: secret
            expires = minOf(registration?.expires ?: Long.MAX_VALUE, local?.expires ?: Long.MAX_VALUE)
            starting = false
            if (registration != null && config != null) worker.execute { poll(registration, config) }
        } catch (e: Exception) { close(); throw e }
    }
    private fun poll(registration: RelayLease, config: RelayConfig) {
        var failures = 0
        val client = requireNotNull(relay)
        try {
            while (!closed.get() && System.currentTimeMillis() < expires) {
                try {
                    val incoming = client.next(registration)
                    failures = 0
                    if (incoming.idle) continue
                    runCatching {
                        client.connect(config, incoming.ticket, minOf(incoming.expires, System.currentTimeMillis() + 15000)) {
                            candidate = it; if (closed.get()) it.close()
                        }.use { socket ->
                            val timeout = Pairing.deadline(socket)
                            try {
                                val exchange = CodeExchange(socket, secret, false)
                                val invitation = Invitation.parse(exchange.receiveSecret())
                                require(invitation.relayTicket != null)
                                check(!closed.get() && System.currentTimeMillis() < expires)
                                exchange.sendSecret("accepted")
                                deliver(invitation)
                            } finally { timeout.cancel(false) }
                        }
                    }
                    candidate = null
                    if (delivered.get()) break
                } catch (e: Exception) {
                    if (closed.get() || (e is RelayFailure && e.status in setOf(404, 410)) || ++failures >= 3) break
                    Thread.sleep(1000L * failures)
                }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally { finish() }
    }
    private fun deliver(invitation: Invitation) {
        if (!closed.get() && delivered.compareAndSet(false, true)) onInvitation(invitation)
    }
    private fun finish() { close(); onClosed() }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        local?.close(); local = null
        runCatching { candidate?.close() }; candidate = null
        lease?.let { relay?.cleanupLease(it) }
        relay?.close(); worker.shutdownNow()
    }
}
