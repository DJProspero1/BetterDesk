package app.betterdesk.core

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

enum class Stage { HOME, INVITATION, PREPARING, WAITING, CONNECTING, CONTROL_PERMISSION, SHARING, VIEWING, ENDED, ERROR }
data class HelpState(
    val stage: Stage = Stage.HOME, val invitation: Invitation? = null,
    val image: ImageBitmap? = null, val frame: Long = 0, val remoteControl: Boolean = false,
    val error: String = "", val peer: String = "", val frameAt: Long = 0,
    val deviceCode: String = "", val codeExpires: Long = 0, val permissionReturned: Boolean = false,
    val codeLoading: Boolean = false, val viaRelay: Boolean = false
)

/** One attended session. A transport handshake never authorizes capture or input. */
class HelpSession(
    val platform: RemotePlatform,
    private val localAddresses: () -> List<String> = Invitation::localAddresses,
    private val bindAddress: java.net.InetAddress? = null,
    private val relayFactory: () -> RelayClient? = { RelayClient() },
    private val localDiscovery: Boolean = true
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutable = MutableStateFlow(HelpState())
    val state = mutable.asStateFlow()
    val performance = FramePerformance()
    internal var failureSummary = ""; private set
    private fun rememberFailure(error: Exception) {
        failureSummary = generateSequence<Throwable>(error) { it.cause }.take(4).joinToString("; ") {
            it.javaClass.simpleName + " at " + it.stackTrace.firstOrNull { frame -> frame.className.startsWith("app.betterdesk") }
        }
    }
    private var work: Job? = null
    @Volatile private var server: SSLServerSocket? = null
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var wire: Wire? = null
    private val authorized = AtomicBoolean(false)
    @Volatile private var generation = 0L
    @Volatile private var sentFrame = 0L
    @Volatile private var sentAt = 0L
    @Volatile private var capturedFrame = 0L
    private var permissionPending = false
    private val frameWindow = FrameWindow(4)
    private var pairingHost: DeviceAvailability? = null
    private var pairingJob: Job? = null
    private var approvalExpiry: Job? = null
    @Volatile private var pairingSocket: java.net.Socket? = null
    @Volatile private var relaySocket: java.net.Socket? = null
    @Volatile private var relayClient: RelayClient? = null
    private var relayCancellation: String? = null
    private val inputQueue = Channel<Pair<Long, Message>>(32)

    init {
        scope.launch(Dispatchers.IO) {
            for ((sessionGeneration, input) in inputQueue) {
                if (sessionGeneration == generation) runCatching { wire?.send(input) }
            }
        }
    }

    fun prepareDeviceCode() {
        if (mutable.value.stage != Stage.HOME || pairingHost != null || pairingJob?.isActive == true) return
        val current = generation
        mutable.value = mutable.value.copy(codeLoading = true)
        pairingJob = scope.launch {
            try {
                platform.keepConnectionAlive(true)
                val host = DeviceAvailability(relayFactory(), bindAddress, localDiscovery,
                    onInvitation = { invitation ->
                        scope.launch { if (generation == current && mutable.value.stage == Stage.HOME) receiveInvitation(invitation) }
                    }, onClosed = {
                        scope.launch {
                            if (generation == current && mutable.value.stage == Stage.HOME) {
                                pairingHost = null
                                mutable.value = mutable.value.copy(deviceCode = "", codeExpires = 0, codeLoading = false)
                                // Expired/attempt-limited codes are never silently kept usable.
                                platform.keepConnectionAlive(false)
                            }
                        }
                    })
                pairingHost = host
                withContext(Dispatchers.IO) { host.start() }
                if (current != generation) { host.close(); return@launch }
                mutable.value = mutable.value.copy(deviceCode = host.code, codeExpires = host.expires, codeLoading = false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (current == generation) finishError("network") }
        }
    }

    fun connectCode(value: String) {
        if (mutable.value.stage != Stage.HOME) return
        val code = Pairing.normalize(value)
        if (!Pairing.validDeviceCode(code) || code == mutable.value.deviceCode) return
        createInvitation(platform.defaultName, code)
    }

    fun acceptInvitation(value: String) {
        if (mutable.value.stage !in setOf(Stage.HOME, Stage.ENDED, Stage.ERROR, Stage.INVITATION)) return
        val parsed = runCatching { Invitation.parse(value).also { require(it.relayTicket == null) } }.getOrElse {
            finishError("invitation"); return
        }
        receiveInvitation(parsed)
    }

    private fun receiveInvitation(parsed: Invitation) {
        stopTransport(preserveConnection = true)
        platform.keepConnectionAlive(true)
        relayCancellation = parsed.relayTicket
        mutable.value = HelpState(Stage.INVITATION, invitation = parsed, peer = parsed.name, viaRelay = parsed.relayTicket != null)
        val current = generation
        approvalExpiry = scope.launch {
            val client = if (parsed.relayTicket != null) relayFactory() else null
            try {
                while (current == generation && mutable.value.stage in setOf(Stage.INVITATION, Stage.CONTROL_PERMISSION)) {
                    val remaining = parsed.expires - System.currentTimeMillis()
                    if (remaining <= 0) { finishError("expired"); break }
                    delay(minOf(1500L, remaining))
                    if (current != generation || mutable.value.stage !in setOf(Stage.INVITATION, Stage.CONTROL_PERMISSION)) break
                    if (client != null && parsed.relayTicket != null) {
                        val active = withContext(Dispatchers.IO) { client.active(parsed.relayTicket) }
                        if (!active && current == generation) { stop(); break }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (current == generation) finishError("network") }
            finally { client?.close() }
        }
    }

    fun createInvitation(name: String, deviceCode: String? = null) {
        stopTransport(preserveConnection = true)
        val current = generation
        val safeName = name.trim().take(40).filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() }.ifBlank { "Helper" }
        mutable.value = HelpState(Stage.PREPARING)
        work = scope.launch {
            try {
                platform.keepConnectionAlive(true)
                val identity = withContext(Dispatchers.IO) { TlsIdentity.create() }
                val hosts = withContext(Dispatchers.IO) { runCatching { localAddresses() }.getOrDefault(emptyList()) }
                var connection: Wire? = null
                var invitation: Invitation? = null
                if (hosts.isNotEmpty() && (deviceCode == null || localDiscovery)) {
                    val listener = withContext(Dispatchers.IO) {
                        (identity.context.serverSocketFactory.createServerSocket(0, 8, bindAddress) as SSLServerSocket).apply {
                            enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                            soTimeout = 1000
                        }
                    }
                    if (current != generation) { listener.close(); return@launch }
                    server = listener
                    val direct = Invitation(hosts, listener.localPort, identity.fingerprint, Invitation.secret(), safeName, System.currentTimeMillis() + Pairing.LIFETIME_MS)
                    if (deviceCode == null) mutable.value = HelpState(Stage.WAITING, invitation = direct)
                    val paired = deviceCode == null || withContext(Dispatchers.IO) {
                        var success = false
                        for (endpoint in Pairing.discover()) {
                            ensureActive()
                            if (runCatching { Pairing.send(endpoint, deviceCode.takeLast(8), direct) {
                                pairingSocket = it; if (current != generation) it.close()
                            } }.isSuccess) { success = true; break }
                        }
                        pairingSocket = null
                        success
                    }
                    if (paired) {
                        invitation = direct
                        mutable.value = HelpState(Stage.WAITING, invitation = direct)
                        connection = withContext(Dispatchers.IO) { accept(listener, direct, current) }
                    }
                    listener.close(); server = null
                }
                if (connection == null) {
                    if (deviceCode?.length != 12) { if (current == generation) finishError("code"); return@launch }
                    val client = relayFactory() ?: error("Internet unavailable")
                    relayClient = client
                    val config = withContext(Dispatchers.IO) { client.config() }
                    val allocation = withContext(Dispatchers.IO) { client.allocate(deviceCode.take(4)).also {
                        // Preserve a cleanup capability even if cancellation wins the IO handoff.
                        if (current != generation) client.cleanupRequest(it.viewerTicket)
                        else relayCancellation = it.viewerTicket
                    } }
                    val remote = Invitation(emptyList(), 0, identity.fingerprint, Invitation.secret(), safeName, allocation.expires, allocation.deviceTicket)
                    invitation = remote
                    withContext(Dispatchers.IO) {
                        client.connect(config, allocation.pairTicket, minOf(allocation.expires, System.currentTimeMillis() + 15000)) {
                            pairingSocket = it; if (current != generation) it.close()
                        }.use { pair ->
                            val timeout = Pairing.deadline(pair)
                            try {
                                val exchange = CodeExchange(pair, deviceCode.takeLast(8), true)
                                exchange.sendSecret(remote.link())
                                check(exchange.receiveSecret() == "accepted")
                            } finally { timeout.cancel(false) }
                        }
                        pairingSocket = null
                    }
                    mutable.value = HelpState(Stage.WAITING, invitation = remote, viaRelay = true)
                    connection = withContext(Dispatchers.IO) {
                        val raw = client.connect(config, allocation.viewerTicket, allocation.expires) {
                            relaySocket = it; if (current != generation) it.close()
                        }
                        val tls = identity.context.socketFactory.createSocket(raw, config.host, config.port, true) as SSLSocket
                        socket = tls; if (current != generation) { tls.close(); error("Canceled") }
                        tls.useClientMode = false
                        configureTls(tls)
                        tls.startHandshake()
                        val connected = Wire(tls)
                        val hello = (connected.read() as? Packet.Control)?.message
                        check(System.currentTimeMillis() < remote.expires && hello?.type == "hello" && TlsIdentity.constantEquals(hello.secret, remote.secret))
                        tls.soTimeout = 15000
                        connected
                    }
                }
                server?.close(); server = null
                if (current != generation) { connection.close(); return@launch }
                wire = connection
                withContext(Dispatchers.IO) {
                    connection.send(Message("accepted", text = safeName, videoCodec = platform.preferredIncomingCodec))
                }
                mutable.value = HelpState(Stage.CONNECTING, invitation = invitation, viaRelay = invitation?.relayTicket != null)
                pump(connection, helper = true, current = current)
            } catch (e: CancellationException) { throw e }
            catch (e: RelayFailure) { if (generation == current) finishError(if (e.status in setOf(404, 410)) "code" else "network") }
            catch (_: java.io.EOFException) { if (generation == current) stop() }
            catch (e: Exception) { rememberFailure(e); if (generation == current) finishError("connection") }
        }
    }

    private fun configureTls(candidate: SSLSocket) {
        candidate.enabledProtocols = candidate.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        candidate.soTimeout = 8000; candidate.tcpNoDelay = true
    }

    private fun accept(listener: SSLServerSocket, invitation: Invitation, current: Long): Wire {
        var failed = 0
        while (current == generation && System.currentTimeMillis() < invitation.expires && failed < 12) {
            val candidate = try { listener.accept() as SSLSocket } catch (_: java.net.SocketTimeoutException) { continue }
            socket = candidate
            try {
                candidate.soTimeout = 4000; candidate.tcpNoDelay = true
                candidate.startHandshake()
                val connection = Wire(candidate)
                val hello = (connection.read() as? Packet.Control)?.message
                if (System.currentTimeMillis() >= invitation.expires || hello?.type != "hello" || !TlsIdentity.constantEquals(hello.secret, invitation.secret)) error("Invalid invitation")
                candidate.soTimeout = 15_000
                return connection
            } catch (_: Exception) { candidate.close(); socket = null; failed++ }
        }
        error("Invitation expired")
    }

    fun allowHelp() {
        if (mutable.value.stage != Stage.INVITATION) return
        if (platform.isAndroid && !platform.controlAvailable()) {
            mutable.value = mutable.value.copy(stage = Stage.CONTROL_PERMISSION)
        } else connectRecipient()
    }

    fun enableControl() {
        if (mutable.value.stage != Stage.CONTROL_PERMISSION) return
        permissionPending = true
        platform.openControlSettings()
    }

    fun openRestrictedSettings() {
        if (mutable.value.stage != Stage.CONTROL_PERMISSION) return
        platform.openAppSettings()
    }

    fun refreshPermissions() {
        if (permissionPending && mutable.value.stage == Stage.CONTROL_PERMISSION && !platform.controlAvailable()) {
            mutable.value = mutable.value.copy(permissionReturned = true)
        }
        if (permissionPending && mutable.value.stage == Stage.CONTROL_PERMISSION && platform.controlAvailable()) {
            permissionPending = false; connectRecipient()
        }
        if (mutable.value.stage == Stage.SHARING && !platform.controlAvailable() && mutable.value.remoteControl) {
            mutable.value = mutable.value.copy(remoteControl = false)
            scope.launch(Dispatchers.IO) { runCatching { wire?.send(Message("capability", control = false)) } }
        }
    }

    fun shareWithoutControl() {
        if (mutable.value.stage != Stage.CONTROL_PERMISSION) return
        permissionPending = false; connectRecipient(viewOnly = true)
    }

    private fun connectRecipient(viewOnly: Boolean = false) {
        val invitation = mutable.value.invitation ?: return
        val current = generation
        mutable.value = mutable.value.copy(stage = Stage.CONNECTING)
        work = scope.launch {
            try {
                // Child socket failures must reach this catch, not cancel the root launch
                // with an uncaught exception on Android's main thread.
                coroutineScope {
                    check(invitation.expires > System.currentTimeMillis())
                    val connection = withContext(Dispatchers.IO) {
                        val context = TlsIdentity.client(invitation.fingerprint)
                        var connected: Wire? = null
                        if (invitation.relayTicket != null) {
                            val client = relayFactory() ?: error("Internet unavailable")
                            relayClient = client
                            val config = client.config()
                            val raw = client.connect(config, invitation.relayTicket, invitation.expires) {
                                relaySocket = it; if (current != generation) it.close()
                            }
                            val candidate = context.socketFactory.createSocket(raw, config.host, config.port, true) as SSLSocket
                            socket = candidate; if (current != generation) { candidate.close(); error("Canceled") }
                            candidate.useClientMode = true
                            configureTls(candidate)
                            candidate.startHandshake()
                            connected = Wire(candidate)
                            connected.send(Message("hello", secret = invitation.secret))
                            val accepted = (connected.read() as? Packet.Control)?.message
                            check(accepted?.type == "accepted")
                            platform.selectOutgoingCodec(accepted!!.videoCodec)
                            candidate.soTimeout = 15000
                        } else for (host in invitation.hosts) {
                            ensureActive()
                            val candidate = context.socketFactory.createSocket() as SSLSocket
                            socket = candidate
                            try {
                                configureTls(candidate)
                                candidate.connect(InetSocketAddress(host, invitation.port), 1500)
                                candidate.startHandshake()
                                connected = Wire(candidate)
                                connected.send(Message("hello", secret = invitation.secret))
                                val accepted = (connected.read() as? Packet.Control)?.message
                                check(accepted?.type == "accepted")
                                platform.selectOutgoingCodec(accepted!!.videoCodec)
                                candidate.soTimeout = 15_000
                                break
                            } catch (_: Exception) { candidate.close(); connected = null }
                        }
                        connected ?: error("Connection failed")
                    }
                    if (current != generation) { connection.close(); return@coroutineScope }
                    wire = connection
                    frameWindow.setCapacity(if (invitation.relayTicket == null) 4 else 12)
                    // One reader and heartbeat remain alive throughout the OS permission prompt.
                    val reader = async { pump(connection, helper = false, current = current) }
                    val recovery = VideoRecovery(platform::requestKeyFrame)
                    val frames = Channel<Packet.Frame>(4, BufferOverflow.DROP_OLDEST) { performance.dropped("queue"); recovery.discard(it) }
                    val allowed = platform.startSharing(
                        // Retain the first capture even if it arrives before startSharing returns.
                        // Transmission still starts only after successful local approval below.
                        onFrame = { if (current == generation) { capturedFrame = it.id; frames.trySend(it) } },
                        onStopped = { scope.launch { if (current == generation) stop() } }
                    )
                    if (!allowed || current != generation) {
                        frames.close(); reader.cancel(); stop(); return@coroutineScope
                    }
                    authorized.set(true)
                    val control = !viewOnly && platform.controlAvailable()
                    mutable.value = mutable.value.copy(stage = Stage.SHARING, remoteControl = control)
                    withContext(Dispatchers.IO) { connection.send(Message("ready", control = control)) }
                    val writer = launch(Dispatchers.IO) {
                        for (frame in frames) {
                            if (!authorized.get() || generation != current) break
                            if (!recovery.accepts(frame)) { performance.dropped("recovery"); recovery.discard(frame); continue }
                            if (!frameWindow.awaitOffer(frame.id)) { performance.dropped("window"); recovery.discard(frame); continue }
                            sentFrame = frame.id; sentAt = System.nanoTime()
                            connection.sendFrame(frame)
                        }
                    }
                    try { reader.await() }
                    finally { writer.cancel(); frames.close() }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: java.io.EOFException) { if (generation == current) stop() }
            catch (e: Exception) { rememberFailure(e); if (generation == current) finishError("connection") }
            finally { if (generation == current) stop() }
        }
    }

    private suspend fun pump(connection: Wire, helper: Boolean, current: Long) = coroutineScope {
        val heartbeat = launch(Dispatchers.IO) {
            while (isActive) {
                delay(750)
                val healthy = !helper && authorized.get() && platform.captureResponsive() && capturedFrame <= sentFrame
                if (healthy) sentAt = System.nanoTime()
                connection.send(Message("ping", frame = if (healthy) sentFrame else 0))
            }
        }
        try {
            withContext(Dispatchers.IO) {
                var ready = false
                var lastReceivedFrame = 0L
                var lastInput = 0L
                while (isActive && current == generation) {
                    when (val packet = connection.read()) {
                        is Packet.Frame -> {
                            check(helper && ready && packet.id > lastReceivedFrame)
                            lastReceivedFrame = packet.id
                            val decodeStarted = System.nanoTime()
                            val image = platform.decode(packet) ?: error("Invalid image")
                            performance.received(packet.jpeg.size, System.nanoTime() - decodeStarted)
                            // StateFlow is thread safe. Waiting for the UI dispatcher here
                            // delays acknowledgements and serializes networking with painting.
                            mutable.update { state ->
                                if (current == generation) state.copy(stage = Stage.VIEWING, image = image, frame = packet.id, frameAt = System.nanoTime()) else state
                            }
                            connection.send(Message("frameAck", frame = packet.id))
                        }
                        is Packet.Control -> when (packet.message.type) {
                            "ping" -> if (helper && packet.message.frame > 0 && packet.message.frame == lastReceivedFrame) {
                                withContext(Dispatchers.Main) {
                                    if (current == generation) mutable.value = mutable.value.copy(frameAt = System.nanoTime())
                                }
                            }
                            "frameAck" -> { check(!helper); frameWindow.acknowledge(packet.message.frame) }
                            "ready" -> {
                                check(helper && !ready); ready = true
                                withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(remoteControl = packet.message.control) }
                            }
                            "capability" -> {
                                check(helper && ready && !packet.message.control)
                                withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(remoteControl = false) }
                            }
                            "input" -> {
                                val message = packet.message
                                val now = System.nanoTime()
                                check(!helper)
                                if (authorized.get() && mutable.value.remoteControl && platform.controlAvailable() && message.isValidInput()
                                    && message.frame <= sentFrame && message.frame >= sentFrame - 60 && now - sentAt < 2_000_000_000 && now - lastInput > 20_000_000) {
                                    lastInput = now
                                    withContext(Dispatchers.Main) { if (authorized.get() && current == generation) platform.applyInput(message) }
                                }
                            }
                            else -> error("Unexpected message")
                        }
                    }
                }
            }
        } finally { heartbeat.cancel() }
    }

    fun input(action: String, x: Float = 0f, y: Float = 0f, endX: Float = x, endY: Float = y, text: String = "") {
        val state = mutable.value
        if (state.stage != Stage.VIEWING || !state.remoteControl || System.nanoTime() - state.frameAt > 2_000_000_000) return
        val message = Message("input", action = action, x = x, y = y, endX = endX, endY = endY, text = text, frame = state.frame)
        if (message.isValidInput()) inputQueue.trySend(generation to message)
    }

    fun stop() { stopTransport(); mutable.value = HelpState(Stage.ENDED) }
    fun home() { stopTransport(); mutable.value = HelpState() }
    private fun finishError(key: String) { stopTransport(); mutable.value = HelpState(Stage.ERROR, error = key) }
    private fun stopTransport(preserveConnection: Boolean = false) {
        authorized.set(false); generation++; permissionPending = false
        pairingHost?.close(); pairingHost = null
        pairingJob?.cancel(); pairingJob = null
        approvalExpiry?.cancel(); approvalExpiry = null
        runCatching { pairingSocket?.close() }; pairingSocket = null
        runCatching { relaySocket?.close() }; relaySocket = null
        relayCancellation?.let { ticket ->
            (relayClient ?: relayFactory())?.let { client -> client.cleanupRequest(ticket); client.close() }
        }
        relayCancellation = null
        relayClient?.close(); relayClient = null
        if (!preserveConnection) platform.keepConnectionAlive(false)
        platform.stopSharing()
        runCatching { socket?.close() }; runCatching { server?.close() }; runCatching { wire?.close() }
        socket = null; server = null; wire = null
        work?.cancel(); work = null; sentFrame = 0; sentAt = 0; capturedFrame = 0; frameWindow.clear(); performance.reset()
    }
    fun dispose() { stopTransport(); inputQueue.close(); scope.cancel() }
}
