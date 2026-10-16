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

enum class Stage { HOME, INVITATION, REMEMBER, PREPARING, WAITING, CONNECTING, SHARING, VIEWING, ENDED, ERROR }
data class HelpState(
    val stage: Stage = Stage.HOME, val invitation: Invitation? = null,
    val image: ImageBitmap? = null, val frame: Long = 0, val remoteControl: Boolean = false,
    val error: String = "", val peer: String = "", val frameAt: Long = 0,
    val deviceCode: String = "", val codeExpires: Long = 0, val permissionReturned: Boolean = false,
    val codeLoading: Boolean = false, val viaRelay: Boolean = false,
    val switchState: String = "", val audioAvailable: Boolean = false, val muted: Boolean = false,
    val inputNotice: String = "", val rememberError: Boolean = false
)

/** One session, authorized locally or by a locally saved device grant. */
class HelpSession(
    val platform: RemotePlatform,
    private val localAddresses: () -> List<String> = Invitation::localAddresses,
    private val bindAddress: java.net.InetAddress? = null,
    private val relayFactory: () -> RelayClient? = { RelayClient() },
    private val localDiscovery: Boolean = true,
    private val discoverLocal: () -> List<Pairing.Endpoint> = Pairing::discover
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutable = MutableStateFlow(HelpState())
    val state = mutable.asStateFlow()
    val performance = FramePerformance()
    val exchange = SessionExchange(platform.exchange, relayFactory, {
        // Compare current acknowledgement pressure, not a stale lifetime FPS average.
        if (viewer) mutable.value.frameAt > 0 && System.nanoTime() - mutable.value.frameAt > 1_250_000_000 && lastReceivedFrame > 0
        else capturedFrame > sentFrame + 2 || frameWindow.congested()
    }, ::sendControl)
    internal var failureSummary = ""; private set
    private fun rememberFailure(error: Exception) {
        failureSummary = generateSequence<Throwable>(error) { it.cause }.take(4).joinToString("; ") {
            it.javaClass.simpleName + " at " + it.stackTrace.firstOrNull { frame -> frame.className.startsWith("app.betterdesk") }
        }
    }
    private fun sessionWriteFailed(error: Exception, current: Long) {
        if (generation != current) return
        rememberFailure(error)
        // An asynchronous write can observe remote Stop before the reader sees EOF.
        // Revoke the session in either order, without replacing Ended with an error.
        if (error is java.io.IOException) stop() else finishError("connection")
    }
    private var work: Job? = null
    @Volatile private var server: SSLServerSocket? = null
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var wire: SessionTransport? = null
    private val authorized = AtomicBoolean(false)
    @Volatile private var generation = 0L
    @Volatile private var sentFrame = 0L
    @Volatile private var sentAt = 0L
    @Volatile private var capturedFrame = 0L
    private var permissionPending = false
    private var rememberedControl = false
    private var pairedRequest = false
    private val mutableRemembered = MutableStateFlow(RememberedDevices.read(platform.rememberedDevices))
    val remembered = mutableRemembered.asStateFlow()
    private val frameWindow = FrameWindow(4)
    private var pairingHost: DeviceAvailability? = null
    private var pairingJob: Job? = null
    private var approvalExpiry: Job? = null
    @Volatile private var pairingSocket: java.net.Socket? = null
    @Volatile private var relaySocket: java.net.Socket? = null
    @Volatile private var relayClient: RelayClient? = null
    private var relayCancellation: String? = null
    private val inputQueue = Channel<Triple<Long, Long, Message>>(128)
    @Volatile private var inputRevision = 0L
    private var pendingPaste: Deferred<Boolean>? = null
    private val controlQueue = Channel<Pair<Long, Message>>(64)
    private val playbackQueue = Channel<Pair<Long, Packet.Audio>>(3, BufferOverflow.DROP_OLDEST)
    @Volatile private var viewer = false
    @Volatile private var epoch = 0L
    @Volatile private var mediaGeneration = 0L
    @Volatile private var incomingReady = false
    private var incomingAudio = false
    private var lastReceivedFrame = 0L
    private var lastReceivedAudio = 0L
    private var lastInput = 0L
    private var inputsInWindow = 0
    private var inputNoticeJob: Job? = null
    private var mediaWork: Job? = null
    private var peerAudio = false
    private var switchToken: String? = null
    private var switchTimeout: Job? = null
    private var locallyStopped = false
    private var remotelyStopped = false

    init {
        scope.launch(Dispatchers.IO) {
            for ((current, message) in controlQueue) {
                try { if (current == generation) wire?.send(message) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { withContext(Dispatchers.Main) { sessionWriteFailed(e, current) } }
            }
        }
        scope.launch {
            for ((sessionGeneration, queuedAt, input) in inputQueue) {
                if (sessionGeneration != generation || input.epoch != epoch || !canInput()) continue
                if (System.nanoTime() - queuedAt > 2_000_000_000) { cancelPendingInput("busy"); continue }
                val revision = inputRevision
                if (input.action == "paste") {
                    val preparation = scope.async { exchange.preparePaste() }
                    pendingPaste = preparation
                    val ready = try { preparation.await() } catch (_: CancellationException) { false }
                        finally { if (pendingPaste === preparation) pendingPaste = null }
                    if (!ready) {
                        if (sessionGeneration == generation && input.epoch == epoch && revision == inputRevision && canInput()) cancelPendingInput("paste")
                        continue
                    }
                }
                if (sessionGeneration != generation || input.epoch != epoch || revision != inputRevision || !canInput()) continue
                // A paste barrier may wait for clipboard bytes. Subsequent keystrokes stay
                // behind it and refer to the current screen only after readiness is confirmed.
                val outgoing = input.copy(frame = mutable.value.frame)
                try { withContext(Dispatchers.IO) {
                    if (sessionGeneration == generation && outgoing.epoch == epoch && revision == inputRevision && canInput()) wire?.send(outgoing)
                } } catch (e: CancellationException) { throw e }
                catch (e: Exception) { sessionWriteFailed(e, sessionGeneration) }
            }
        }
        scope.launch(Dispatchers.IO) {
            for ((sessionGeneration, audio) in playbackQueue) {
                if (sessionGeneration == generation && audio.epoch == epoch && viewer && incomingReady && !mutable.value.muted && mutable.value.switchState != "switching") {
                    runCatching { platform.playAudio(audio) }.onFailure {
                        withContext(Dispatchers.Main) {
                            if (sessionGeneration == generation && audio.epoch == epoch && viewer && !mutable.value.muted && mutable.value.switchState != "switching") {
                                platform.stopAudioPlayback()
                                mutable.update { it.copy(audioAvailable = false) }
                            }
                        }
                    }
                }
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
                        scope.launch { if (generation == current && mutable.value.stage == Stage.HOME) receiveInvitation(invitation, paired = true) }
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

    private fun receiveInvitation(parsed: Invitation, paired: Boolean = false) {
        stopTransport(preserveConnection = true)
        platform.keepConnectionAlive(true)
        relayCancellation = parsed.relayTicket
        pairedRequest = paired
        mutable.value = HelpState(Stage.INVITATION, invitation = parsed, peer = parsed.name, viaRelay = parsed.relayTicket != null)
        val current = generation
        approvalExpiry = scope.launch {
            val client = if (parsed.relayTicket != null) relayFactory() else null
            try {
                while (current == generation && mutable.value.stage in setOf(Stage.INVITATION, Stage.REMEMBER)) {
                    val remaining = parsed.expires - System.currentTimeMillis()
                    if (remaining <= 0) { finishError("expired"); break }
                    delay(minOf(1500L, remaining))
                    if (current != generation || mutable.value.stage !in setOf(Stage.INVITATION, Stage.REMEMBER)) break
                    if (client != null && parsed.relayTicket != null) {
                        val active = withContext(Dispatchers.IO) { client.active(parsed.relayTicket) }
                        if (!active && current == generation) { stop(); break }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (current == generation) finishError("network") }
            finally { client?.close() }
        }
        if (paired && saved(parsed)) { rememberedControl = true; connectRecipient() }
    }

    private fun saved(invitation: Invitation) = invitation.verifiedDevice() && remembered.value.any { it.key == invitation.deviceKey }

    fun forgetDevice(key: String) {
        if (mutable.value.stage != Stage.HOME) return
        val remaining = remembered.value.filterNot { it.key == key }
        runCatching { platform.rememberedDevices = RememberedDevices.encode(remaining) }
            .onSuccess { mutableRemembered.value = remaining; mutable.value = mutable.value.copy(rememberError = false) }
            .onFailure { mutable.value = mutable.value.copy(rememberError = true) }
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
                val deviceIdentity = withContext(Dispatchers.IO) { platform.deviceIdentity }
                val hosts = withContext(Dispatchers.IO) { runCatching { localAddresses() }.getOrDefault(emptyList()) }
                var connection: SessionTransport? = null
                var invitation: Invitation? = null
                if (hosts.isNotEmpty() && (deviceCode == null || localDiscovery)) {
                    var paired = false
                    var listener: SSLServerSocket? = null
                    try {
                        val localListener = withContext(Dispatchers.IO) {
                            (identity.context.serverSocketFactory.createServerSocket(0, 8, bindAddress) as SSLServerSocket).apply {
                                listener = this // Keep cleanup ownership if cancellation wins the IO handoff.
                                enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                                soTimeout = 1000
                            }
                        }
                        if (current != generation) { localListener.close(); return@launch }
                        server = localListener
                        val direct = Invitation(hosts, localListener.localPort, identity.fingerprint, Invitation.secret(), safeName, System.currentTimeMillis() + Pairing.LIFETIME_MS).let { if (deviceIdentity != null) it.signed(deviceIdentity) else it }
                        if (deviceCode == null) mutable.value = HelpState(Stage.WAITING, invitation = direct)
                        paired = deviceCode == null || withContext(Dispatchers.IO) {
                            val deadline = System.nanoTime() + 3_500_000_000L
                            var success = false
                            for (endpoint in discoverLocal()) {
                                ensureActive()
                                val remaining = (deadline - System.nanoTime()) / 1_000_000
                                if (remaining <= 0) break
                                if (runCatching { Pairing.send(endpoint, deviceCode.takeLast(8), direct, remaining) {
                                    if (current == generation) pairingSocket = it else it.close()
                                } }.onFailure { if (it is Exception) rememberFailure(it) }.isSuccess) { success = true; break }
                            }
                            if (current == generation) pairingSocket = null
                            success
                        }
                        if (paired) {
                            invitation = direct
                            mutable.value = HelpState(Stage.WAITING, invitation = direct)
                            connection = withContext(Dispatchers.IO) { accept(localListener, direct, current) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        // Fall back only before an approval has been delivered. A decline,
                        // cancellation or failed established session never creates a new request.
                        if (paired || deviceCode?.length != 12) throw e
                    } finally {
                        runCatching { listener?.close() }; if (server === listener) server = null
                        if (current == generation) { runCatching { pairingSocket?.close() }; pairingSocket = null }
                    }
                    ensureActive()
                    if (current != generation) return@launch
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
                    val remote = Invitation(emptyList(), 0, identity.fingerprint, Invitation.secret(), safeName, allocation.expires, allocation.deviceTicket).let { if (deviceIdentity != null) it.signed(deviceIdentity) else it }
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
                        check(System.currentTimeMillis() < remote.expires && hello?.type == "hello" && hello.protocol == 4 && TlsIdentity.constantEquals(hello.secret, remote.secret))
                        tls.soTimeout = 15000
                        connected
                    }
                }
                server?.close(); server = null
                if (current != generation) { connection.close(); return@launch }
                wire = connection
                viewer = true
                withContext(Dispatchers.IO) {
                    connection.send(Message("accepted", text = safeName, videoCodec = platform.preferredIncomingCodec, audio = platform.receivesAudio, protocol = 4))
                }
                mutable.value = HelpState(Stage.CONNECTING, invitation = invitation, viaRelay = invitation?.relayTicket != null)
                pump(connection, current)
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

    private fun accept(listener: SSLServerSocket, invitation: Invitation, current: Long): SessionTransport {
        var failed = 0
        while (current == generation && System.currentTimeMillis() < invitation.expires && failed < 12) {
            val candidate = try { listener.accept() as SSLSocket } catch (_: java.net.SocketTimeoutException) { continue }
            socket = candidate
            try {
                candidate.soTimeout = 4000; candidate.tcpNoDelay = true
                candidate.startHandshake()
                val connection = Wire(candidate)
                val hello = (connection.read() as? Packet.Control)?.message
                if (System.currentTimeMillis() >= invitation.expires || hello?.type != "hello" || hello.protocol != 4 || !TlsIdentity.constantEquals(hello.secret, invitation.secret)) error("Invalid invitation")
                candidate.soTimeout = 15_000
                return connection
            } catch (_: Exception) { candidate.close(); socket = null; failed++ }
        }
        error("Invitation expired")
    }

    /** The local-only choice is completed before any capture or input can start. */
    fun allowHelp() {
        if (mutable.value.stage != Stage.INVITATION) return
        val invitation = mutable.value.invitation ?: return
        if (pairedRequest && invitation.verifiedDevice() && !saved(invitation)) {
            mutable.value = mutable.value.copy(stage = Stage.REMEMBER)
        } else connectRecipient()
    }

    fun finishRemember(always: Boolean) {
        if (mutable.value.stage != Stage.REMEMBER) return
        val invitation = mutable.value.invitation ?: return
        if (invitation.expires <= System.currentTimeMillis()) { finishError("expired"); return }
        if (always) {
            if (!pairedRequest || !invitation.verifiedDevice() || remembered.value.size >= RememberedDevices.LIMIT) {
                mutable.value = mutable.value.copy(rememberError = true); return
            }
            val updated = remembered.value + RememberedDevice(invitation.deviceKey, invitation.name)
            if (runCatching { platform.rememberedDevices = RememberedDevices.encode(updated) }.isFailure) {
                mutable.value = mutable.value.copy(rememberError = true); return
            }
            mutableRemembered.value = updated
            rememberedControl = true
        }
        connectRecipient()
    }

    private fun canOfferControl() = mutable.value.let {
        it.stage == Stage.SHARING && it.switchState.isEmpty() && !it.remoteControl && platform.isAndroid
    }

    fun enableControl() {
        if (!canOfferControl()) return
        permissionPending = true
        platform.openControlSettings()
    }

    fun openRestrictedSettings() {
        if (!canOfferControl()) return
        platform.openAppSettings()
    }

    fun refreshPermissions() {
        // Only the person's own request during this session turns control on.
        if (permissionPending && canOfferControl()) {
            if (platform.controlAvailable()) {
                permissionPending = false
                mutable.value = mutable.value.copy(remoteControl = true, permissionReturned = false)
                sendControl(Message("capability", control = true, epoch = epoch))
            } else mutable.value = mutable.value.copy(permissionReturned = true)
        }
        if (mutable.value.stage == Stage.SHARING && !platform.controlAvailable() && mutable.value.remoteControl) {
            mutable.value = mutable.value.copy(remoteControl = false)
            sendControl(Message("capability", control = false, epoch = epoch))
        }
    }

    private fun connectRecipient() {
        if (wire != null && !viewer) {
            mutable.value = mutable.value.copy(stage = Stage.CONNECTING)
            startMedia(wire!!, generation)
            return
        }
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
                        var connected: SessionTransport? = null
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
                            connected.send(Message("hello", secret = invitation.secret, protocol = 4))
                            val accepted = (connected.read() as? Packet.Control)?.message
                            check(accepted?.type == "accepted" && accepted.protocol == 4)
                            platform.selectOutgoingCodec(accepted!!.videoCodec)
                            peerAudio = accepted.audio
                            candidate.soTimeout = 15000
                        } else for (host in invitation.observedHost?.let(::listOf) ?: invitation.hosts) {
                            ensureActive()
                            val candidate = context.socketFactory.createSocket() as SSLSocket
                            socket = candidate
                            try {
                                configureTls(candidate)
                                candidate.connect(InetSocketAddress(host, invitation.port), 1500)
                                candidate.startHandshake()
                                connected = Wire(candidate)
                                connected.send(Message("hello", secret = invitation.secret, protocol = 4))
                                val accepted = (connected.read() as? Packet.Control)?.message
                                check(accepted?.type == "accepted" && accepted.protocol == 4)
                                platform.selectOutgoingCodec(accepted!!.videoCodec)
                                peerAudio = accepted.audio
                                candidate.soTimeout = 15_000
                                break
                            } catch (_: Exception) { candidate.close(); connected = null }
                        }
                        connected ?: error("Connection failed")
                    }
                    if (current != generation) { connection.close(); return@coroutineScope }
                    wire = connection
                    viewer = false
                    startMedia(connection, current)
                    // One reader and heartbeat remain alive throughout OS permissions.
                    pump(connection, current)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: java.io.EOFException) { if (generation == current) stop() }
            catch (e: Exception) { rememberFailure(e); if (generation == current) finishError("connection") }
            finally { if (generation == current) stop() }
        }
    }

    private fun startMedia(connection: SessionTransport, current: Long) {
        if (mediaWork?.isActive == true) return
        val media = ++mediaGeneration
        val streamEpoch = epoch
        frameWindow.setCapacity(if (mutable.value.viaRelay) 12 else 4)
        mediaWork = scope.launch {
            val recovery = VideoRecovery(platform::requestKeyFrame)
            val frames = Channel<Packet.Frame>(4, BufferOverflow.DROP_OLDEST) { performance.dropped("queue"); recovery.discard(it) }
            val audio = Channel<Packet.Audio>(3, BufferOverflow.DROP_OLDEST)
            try {
                coroutineScope {
                    val allowed = platform.startSharing(
                        onFrame = { if (current == generation && media == mediaGeneration) { capturedFrame = it.id; frames.trySend(it.copy(epoch = streamEpoch)) } },
                        onStopped = { scope.launch { if (current == generation && media == mediaGeneration) stop() } }
                    )
                    if (!allowed || current != generation || media != mediaGeneration) { if (current == generation) stop(); return@coroutineScope }
                    authorized.set(true)
                    val control = platform.controlAvailable() && (!platform.isAndroid || rememberedControl)
                    val sound = peerAudio && withContext(Dispatchers.IO) {
                        runCatching { platform.startAudio { if (current == generation && media == mediaGeneration && authorized.get()) audio.trySend(it.copy(epoch = streamEpoch)) } }.getOrDefault(false)
                    }
                    if (current != generation || media != mediaGeneration) return@coroutineScope
                    mutable.value = mutable.value.copy(stage = Stage.SHARING, remoteControl = control, audioAvailable = sound)
                    withContext(Dispatchers.IO) { connection.send(Message("ready", control = control, audio = sound, epoch = streamEpoch)) }
                    exchange.start(connection.peerAddress, mutable.value.viaRelay)
                    launch(Dispatchers.IO) {
                        for (frame in frames) {
                            if (!authorized.get() || current != generation || media != mediaGeneration) break
                            if (!recovery.accepts(frame)) { performance.dropped("recovery"); recovery.discard(frame); continue }
                            if (!frameWindow.awaitOffer(frame.id)) { performance.dropped("window"); recovery.discard(frame); continue }
                            if (!authorized.get() || current != generation || media != mediaGeneration) break
                            sentFrame = frame.id; sentAt = System.nanoTime()
                            connection.sendFrame(frame)
                        }
                    }
                    launch(Dispatchers.IO) {
                        for (packet in audio) {
                            if (!authorized.get() || current != generation || media != mediaGeneration) break
                            connection.sendAudio(packet)
                        }
                    }
                    awaitCancellation()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { sessionWriteFailed(e, current) }
            finally { frames.close(); audio.close() }
        }
    }

    private fun sendControl(message: Message) {
        if (wire == null) return
        if (!controlQueue.trySend(generation to message).isSuccess) scope.launch { finishError("connection") }
    }

    fun toggleSound() {
        if (!viewer || mutable.value.stage != Stage.VIEWING) return
        mutable.value = mutable.value.copy(muted = !mutable.value.muted)
        if (mutable.value.muted) { while (playbackQueue.tryReceive().isSuccess) Unit; platform.stopAudioPlayback() }
        else platform.startAudioPlayback()
    }

    fun requestSwitch() {
        if (mutable.value.stage !in setOf(Stage.SHARING, Stage.VIEWING) || switchToken != null) return
        cancelPendingInput()
        val token = Invitation.secret()
        switchToken = token
        mutable.value = mutable.value.copy(switchState = "sent")
        armSwitchTimeout(token)
        sendControl(Message("switchRequest", text = token, epoch = epoch))
    }

    private fun armSwitchTimeout(token: String) {
        switchTimeout?.cancel()
        switchTimeout = scope.launch { delay(30_000); if (switchToken == token) cancelSwitch() }
    }

    fun cancelSwitch() {
        if (mutable.value.switchState !in setOf("sent", "incoming")) return
        val token = switchToken ?: return
        clearSwitch()
        sendControl(Message("switchCancel", text = token, epoch = epoch))
    }

    private fun clearSwitch() {
        switchTimeout?.cancel(); switchTimeout = null; switchToken = null
        locallyStopped = false; remotelyStopped = false
        mutable.value = mutable.value.copy(switchState = "")
    }

    fun acceptSwitch() {
        if (mutable.value.switchState != "incoming") return
        val token = switchToken ?: return
        val current = generation
        // Disable remote input immediately, before the outgoing acceptance is scheduled.
        mutable.value = mutable.value.copy(switchState = "switching")
        switchTimeout?.cancel()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { wire?.send(Message("switchAccepted", text = token, epoch = epoch)) }
                if (current == generation) stopForSwitch(token, current)
            } catch (e: Exception) { if (current == generation) finishError("connection") }
        }
    }

    private suspend fun stopForSwitch(token: String, current: Long) {
        if (token != switchToken || current != generation || locallyStopped) return
        switchTimeout?.cancel()
        mutable.value = mutable.value.copy(switchState = "switching", remoteControl = false)
        authorized.set(false); mediaGeneration++
        platform.keepConnectionAlive(true)
        platform.stopAudioCapture(); platform.stopAudioPlayback(); platform.stopSharing()
        while (playbackQueue.tryReceive().isSuccess) Unit
        val previous = mediaWork; mediaWork = null
        withTimeout(5000) { previous?.cancelAndJoin() }
        if (current != generation || switchToken != token) return
        // Every old media write has ended before this marker goes on the same TLS stream.
        withContext(Dispatchers.IO) { wire!!.send(Message("switchStopped", text = token, epoch = epoch + 1,
            videoCodec = platform.preferredIncomingCodec, audio = platform.receivesAudio)) }
        locallyStopped = true
        finishSwitch(current)
    }

    private fun finishSwitch(current: Long) {
        if (!locallyStopped || !remotelyStopped || current != generation) return
        viewer = !viewer; epoch++
        incomingReady = false; incomingAudio = false; lastReceivedFrame = 0; lastReceivedAudio = 0; lastInput = 0
        sentFrame = 0; sentAt = 0; capturedFrame = 0; frameWindow.clear(); performance.reset()
        clearSwitch()
        mutable.value = HelpState(stage = Stage.CONNECTING, peer = mutable.value.peer, viaRelay = mutable.value.viaRelay)
        if (!viewer) { permissionPending = false; startMedia(wire!!, current) }
    }

    private suspend fun switchMessage(message: Message, current: Long) {
        if (current != generation) return
        require(message.text.matches(Regex("[A-Za-z0-9_-]{43}")))
        when (message.type) {
            "switchRequest" -> {
                if (message.epoch != epoch || switchToken != null || mutable.value.stage !in setOf(Stage.SHARING, Stage.VIEWING)) {
                    sendControl(Message("switchCancel", text = message.text, epoch = message.epoch)); return
                }
                switchToken = message.text
                cancelPendingInput()
                mutable.value = mutable.value.copy(switchState = "incoming")
                armSwitchTimeout(message.text)
            }
            "switchCancel" -> if (message.epoch == epoch && switchToken == message.text && mutable.value.switchState in setOf("sent", "incoming")) clearSwitch()
            "switchAccepted" -> if (message.epoch == epoch && switchToken == message.text && mutable.value.switchState == "sent") {
                mutable.value = mutable.value.copy(switchState = "switching")
                stopForSwitch(message.text, current)
            }
            "switchStopped" -> {
                check(message.epoch == epoch + 1 && switchToken == message.text && mutable.value.switchState == "switching")
                check(message.videoCodec in setOf("jpeg", "avc"))
                peerAudio = message.audio
                platform.selectOutgoingCodec(message.videoCodec)
                remotelyStopped = true
                finishSwitch(current)
            }
        }
    }

    private suspend fun pump(connection: SessionTransport, current: Long) = try { coroutineScope {
        val heartbeat = launch(Dispatchers.IO) {
            while (isActive) {
                delay(750)
                val healthy = !viewer && authorized.get() && platform.captureResponsive() && capturedFrame <= sentFrame
                if (healthy) sentAt = System.nanoTime()
                connection.send(Message("ping", frame = if (healthy) sentFrame else 0, epoch = epoch))
            }
        }
        try {
            withContext(Dispatchers.IO) {
                while (isActive && current == generation) {
                    when (val packet = connection.read()) {
                        is Packet.Frame -> {
                            if (packet.epoch != epoch || mutable.value.switchState == "switching") continue
                            check(viewer && incomingReady && packet.id > lastReceivedFrame)
                            lastReceivedFrame = packet.id
                            val decodeStarted = System.nanoTime()
                            val image = platform.decode(packet) ?: error("Invalid image")
                            performance.received(packet.jpeg.size, System.nanoTime() - decodeStarted)
                            // StateFlow is thread safe. Waiting for the UI dispatcher here
                            // delays acknowledgements and serializes networking with painting.
                            mutable.update { state ->
                                if (current == generation) state.copy(stage = Stage.VIEWING, image = image, frame = packet.id, frameAt = System.nanoTime()) else state
                            }
                            connection.send(Message("frameAck", frame = packet.id, epoch = packet.epoch))
                        }
                        is Packet.Audio -> {
                            if (packet.epoch != epoch || mutable.value.switchState == "switching") continue
                            check(viewer && incomingReady && incomingAudio && packet.id > lastReceivedAudio)
                            lastReceivedAudio = packet.id
                            if (!mutable.value.muted) playbackQueue.trySend(current to packet)
                        }
                        is Packet.Control -> when (packet.message.type) {
                            "exchangeClipboard", "exchangeText", "exchangeOffer", "exchangeRequest", "exchangeCancel", "exchangeFailure", "exchangeBarrier", "exchangeBarrierAck" ->
                                withContext(Dispatchers.Main) { if (current == generation) exchange.handle(packet.message) }
                            "switchRequest", "switchCancel", "switchAccepted", "switchStopped" -> withContext(Dispatchers.Main) { switchMessage(packet.message, current) }
                            "ping" -> if (viewer && packet.message.epoch == epoch && packet.message.frame > 0 && packet.message.frame == lastReceivedFrame) {
                                withContext(Dispatchers.Main) {
                                    if (current == generation) mutable.value = mutable.value.copy(frameAt = System.nanoTime())
                                }
                            }
                            "frameAck" -> if (packet.message.epoch == epoch && !viewer) frameWindow.acknowledge(packet.message.frame)
                            "ready" -> {
                                check(viewer && !incomingReady && packet.message.epoch == epoch); incomingReady = true; incomingAudio = packet.message.audio
                                withContext(Dispatchers.Main) {
                                    mutable.value = mutable.value.copy(remoteControl = packet.message.control, audioAvailable = packet.message.audio)
                                    exchange.start(connection.peerAddress, mutable.value.viaRelay)
                                    if (packet.message.audio && !mutable.value.muted) platform.startAudioPlayback()
                                }
                            }
                            "capability" -> {
                                if (packet.message.epoch != epoch || mutable.value.switchState == "switching") continue
                                check(viewer && incomingReady)
                                withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(remoteControl = packet.message.control) }
                            }
                            "inputUnavailable" -> withContext(Dispatchers.Main) {
                                if (current == generation && viewer && packet.message.epoch == epoch) showInputNotice("unavailable")
                            }
                            "input" -> {
                                val message = packet.message
                                val now = System.nanoTime()
                                if (message.epoch != epoch || viewer) continue
                                if (authorized.get() && mutable.value.switchState.isEmpty() && mutable.value.remoteControl && platform.controlAvailable() && message.isValidInput()
                                    && message.frame <= sentFrame && message.frame >= sentFrame - 60 && now - sentAt < 2_000_000_000) {
                                    if (now - lastInput >= 1_000_000_000) { lastInput = now; inputsInWindow = 0 }
                                    check(++inputsInWindow <= 256) { "Input rate exceeded" }
                                    withContext(Dispatchers.Main) {
                                        if (authorized.get() && current == generation && message.epoch == epoch && mutable.value.switchState.isEmpty()) {
                                            val submitted = platform.submitInput(message)
                                            if (!submitted && message.action !in setOf("tap", "right", "swipe", "scrollUp", "scrollDown"))
                                                sendControl(Message("inputUnavailable", epoch = epoch))
                                        }
                                    }
                                }
                            }
                            else -> error("Unexpected message")
                        }
                    }
                }
            }
        } finally { heartbeat.cancel() }
    } } catch (error: java.io.IOException) {
        // Reads, acknowledgements and heartbeat writes race when either peer stops.
        throw java.io.EOFException("Session transport closed").apply { initCause(error) }
    }

    private fun canInput(): Boolean {
        val state = mutable.value
        return state.stage == Stage.VIEWING && state.switchState.isEmpty() && state.remoteControl && System.nanoTime() - state.frameAt <= 2_000_000_000
    }
    fun input(action: String, x: Float = 0f, y: Float = 0f, endX: Float = x, endY: Float = y, text: String = "", key: String = "", modifiers: Int = 0): Boolean {
        if (!canInput()) return false
        val message = Message("input", action = action, x = x, y = y, endX = endX, endY = endY, text = text, frame = mutable.value.frame, epoch = epoch, key = key, modifiers = modifiers)
        if (!message.isValidInput()) return false
        if (!inputQueue.trySend(Triple(generation, System.nanoTime(), message)).isSuccess) { cancelPendingInput("busy"); return false }
        return true
    }
    fun typeText(text: String) {
        for (part in inputTextChunks(text.replace("\r\n", "\n").replace('\r', '\n'))) if (!input("text", text = part)) break
    }
    fun key(key: String, modifiers: Int = 0) { input("key", key = key, modifiers = modifiers) }
    fun paste(modifiers: Int = 0) { input("paste", modifiers = modifiers) }
    fun releaseKeyboard() { cancelPendingInput() }
    private fun cancelPendingInput(notice: String = "") {
        inputRevision++
        pendingPaste?.cancel(); pendingPaste = null
        while (inputQueue.tryReceive().isSuccess) Unit
        if (notice.isNotEmpty()) showInputNotice(notice)
    }
    private fun showInputNotice(notice: String) {
        inputNoticeJob?.cancel()
        mutable.update { it.copy(inputNotice = notice) }
        inputNoticeJob = scope.launch { delay(5000); mutable.update { it.copy(inputNotice = "") } }
    }

    fun stop() { stopTransport(); mutable.value = HelpState(Stage.ENDED) }
    fun home() { stopTransport(); mutable.value = HelpState() }
    private fun finishError(key: String) { stopTransport(); mutable.value = HelpState(Stage.ERROR, error = key) }
    private fun stopTransport(preserveConnection: Boolean = false) {
        authorized.set(false); generation++; mediaGeneration++; permissionPending = false
        rememberedControl = false; pairedRequest = false
        cancelPendingInput(); inputNoticeJob?.cancel(); inputNoticeJob = null
        exchange.stop()
        switchTimeout?.cancel(); switchTimeout = null; switchToken = null
        locallyStopped = false; remotelyStopped = false
        mediaWork?.cancel(); mediaWork = null
        platform.stopAudioCapture(); platform.stopAudioPlayback()
        while (playbackQueue.tryReceive().isSuccess) Unit
        viewer = false; epoch = 0; incomingReady = false; incomingAudio = false; peerAudio = false
        lastReceivedFrame = 0; lastReceivedAudio = 0; lastInput = 0
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
    fun dispose() { stopTransport(); exchange.dispose(); inputQueue.close(); controlQueue.close(); playbackQueue.close(); scope.cancel() }
}
