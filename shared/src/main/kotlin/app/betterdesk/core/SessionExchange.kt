package app.betterdesk.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Files and clipboard belong to this approved session, independently of screen direction. */
class SessionExchange(private val platform: ExchangePlatform?, private val relayFactory: () -> RelayClient?,
    private val congested: () -> Boolean, private val send: (Message) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutable = MutableStateFlow(ExchangeState())
    val state = mutable.asStateFlow()
    private val json = Json { encodeDefaults = true }
    private val sources = ConcurrentHashMap<String, List<LocalFile>>()
    private val transports = ConcurrentHashMap<String, TransferTransport>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val downloads = ConcurrentHashMap<String, CompletableFuture<File>>()
    private var peerAddress: InetAddress? = null
    private var viaRelay = false
    @Volatile private var generation = 0L
    val sessionToken: Long get() = generation
    private var root: File? = null
    private var incomingReserved = 0L
    private var lastClipboard = 0L
    private var noticeJob: Job? = null
    private var clipboardOffer: String? = null
    private var publishedClipboard = ""
    private var receivedClipboard = ""
    private val pasteWaiters = HashMap<String, () -> Unit>()
    private val clipboardImages = HashSet<String>()
    private val saveJobs = HashMap<String, Job>()
    private val rootLock = Any()
    private var messagesAt = System.nanoTime()
    private var messagesInWindow = 0
    private var barriersInFlight = 0

    fun start(address: InetAddress, relay: Boolean) {
        if (mutable.value.connected || platform == null) return
        peerAddress = address; viaRelay = relay
        mutable.value = ExchangeState(connected = true)
        watchClipboard()
        send(Message("exchangeClipboard", control = true))
    }

    fun setClipboard(enabled: Boolean) {
        if (!mutable.value.connected || platform == null) return
        mutable.update { it.copy(clipboard = enabled) }
        watchClipboard()
        if (!enabled) {
            platform.clearRemoteClipboard()
            mutable.value.items.filter { it.offer.kind != "files" && it.phase != TransferPhase.DONE }.forEach { cancel(it.offer.id) }
        }
        send(Message("exchangeClipboard", control = enabled))
    }
    private fun watchClipboard() {
        val current = generation
        platform?.watchClipboard(if (mutable.value.clipboard && mutable.value.peerClipboard && platform.automaticClipboard) {
            { value -> if (current == generation) clipboardChanged(value) }
        } else null)
    }

    fun sendClipboard() {
        if (!mutable.value.connected || !mutable.value.clipboard || platform == null) return
        val current = generation
        scope.launch {
            val value = withContext(Dispatchers.IO) { runCatching { platform.readClipboard() }.getOrNull() }
            if (current != generation) return@launch
            if (value == null) notice("emptyClipboard") else clipboardChanged(value)
        }
    }
    fun shareText(text: String, expectedSession: Long = generation) {
        if (expectedSession != generation || !mutable.value.connected) return
        if (!mutable.value.clipboard || !mutable.value.peerClipboard) { notice("clipboardDisabled"); return }
        publishClipboard(ClipboardValue.Text(text))
    }

    private fun clipboardChanged(value: ClipboardValue) {
        val current = generation
        scope.launch {
            if (current != generation || !mutable.value.connected || !mutable.value.clipboard || !mutable.value.peerClipboard) return@launch
            if (System.currentTimeMillis() - lastClipboard < 250) return@launch
            lastClipboard = System.currentTimeMillis()
            publishClipboard(value)
        }
    }
    private fun publishClipboard(value: ClipboardValue): Boolean {
            return when (value) {
                is ClipboardValue.Unavailable -> { notice(value.reason); false }
                is ClipboardValue.Text -> {
                    if (value.text.toByteArray().size > 8000) { notice("clipboardTooLarge"); return false }
                    val revision = UUID.randomUUID().toString()
                    val message = Message("exchangeText", text = value.text, action = revision)
                    if (json.encodeToString(message).toByteArray().size > Wire.MAX_MESSAGE) { notice("clipboardTooLarge"); return false }
                    clipboardOffer?.let { id -> item(id)?.takeIf { !it.active }?.let { cancel(id) } }
                    clipboardOffer = null
                    publishedClipboard = revision
                    send(message); notice("clipboardSent"); true
                }
                is ClipboardValue.Image -> {
                    if (value.png.size > FileOffer.MAX_IMAGE_BYTES) { notice("clipboardTooLarge"); return false }
                    offer(listOf(LocalFile(FileEntry("Screenshot.png", value.png.size.toLong())) { value.png.inputStream() }), "image")
                }
                is ClipboardValue.Files -> offer(value.files, "clipboard")
            }
    }

    fun beforePaste(action: () -> Unit) {
        if (!mutable.value.connected || platform == null) return
        if (!mutable.value.clipboard || !mutable.value.peerClipboard) { action(); return }
        if (pasteWaiters.isNotEmpty()) return
        val current = generation
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { platform.readClipboard() }
                if (current != generation) return@launch
                if (value != null && !publishClipboard(value)) return@launch
                val token = UUID.randomUUID().toString()
                pasteWaiters[token] = action
                send(Message("exchangeBarrier", text = token, secret = if (value != null) publishedClipboard else ""))
                delay(15_000)
                if (pasteWaiters.remove(token) != null && current == generation) notice("clipboardFailed")
            } catch (_: Exception) { if (current == generation) notice("clipboardFailed") }
        }
    }

    fun pickFiles() {
        if (!mutable.value.connected || platform == null) return
        val current = generation
        scope.launch {
            try {
                val files = platform.chooseFiles()
                if (current == generation && files.isNotEmpty()) offer(files)
            } catch (_: CancellationException) { }
            catch (error: Exception) { if (current == generation) notice(if (error is IllegalArgumentException) "selectionLimit" else "selectionFailed") }
        }
    }

    /** Also used by native Android Sharesheet and Windows drag/drop. */
    fun offer(files: List<LocalFile>, kind: String = "files", expectedSession: Long = generation): Boolean {
        if (expectedSession != generation) return false
        if (!mutable.value.connected) { notice("connectFirst"); return false }
        if (files.isEmpty()) return false
        return try {
            val offer = FileOffer(UUID.randomUUID().toString().replace("-", ""), files.map { it.entry }, kind)
            offer.validate()
            val encoded = json.encodeToString(offer)
            require(encoded.toByteArray().size <= 12_000)
            if (mutable.value.items.count { !it.incoming && (it.active || it.phase == TransferPhase.OFFERED) } >= 4) { notice("busy"); return false }
            if (kind != "files") {
                clipboardOffer?.let { previous ->
                    item(previous)?.takeIf { !it.active }?.let { cancel(previous) }
                }
                clipboardOffer = offer.id
                publishedClipboard = offer.id
            }
            sources[offer.id] = files
            add(TransferItem(offer, incoming = false))
            send(Message("exchangeOffer", text = encoded)); true
        } catch (_: Exception) { notice("selectionLimit"); false }
    }

    fun handle(message: Message) {
        if (!mutable.value.connected) return
        val platform = platform ?: return
        val now = System.nanoTime()
        if (now - messagesAt >= 1_000_000_000) { messagesAt = now; messagesInWindow = 0 }
        require(++messagesInWindow <= 60) { "Exchange rate limit" }
        when (message.type) {
            "exchangeBarrier" -> {
                require(message.text.matches(Regex("[a-f0-9-]{36}")))
                require(barriersInFlight < 4)
                barriersInFlight++
                val current = generation
                scope.launch {
                    try {
                    var ready = runCatching { platform.clipboardReady() }.isSuccess
                    val image = mutable.value.items.lastOrNull { it.incoming && it.offer.kind == "image" }
                    if (clipboardImages.isNotEmpty()) {
                        // Clipboard images arrive on their own data connection before paste is allowed.
                        ready = withTimeoutOrNull(12_000) {
                            while (clipboardImages.isNotEmpty()) delay(50)
                            image != null && item(image.offer.id)?.phase == TransferPhase.DONE
                        } == true && runCatching { platform.clipboardReady() }.isSuccess
                    }
                    ready = ready && (message.secret.isEmpty() || message.secret == receivedClipboard)
                    if (current == generation) send(Message("exchangeBarrierAck", text = message.text, control = ready))
                    } finally { if (current == generation) barriersInFlight-- }
                }
            }
            "exchangeBarrierAck" -> pasteWaiters.remove(message.text)?.let { if (message.control) it() else notice("clipboardFailed") }
            "exchangeClipboard" -> {
                mutable.update { it.copy(peerClipboard = message.control) }
                watchClipboard()
                if (!message.control) {
                    receivedClipboard = ""
                    platform.clearRemoteClipboard()
                    mutable.value.items.filter { it.offer.kind != "files" && it.phase != TransferPhase.DONE }.forEach { cancel(it.offer.id) }
                }
            }
            "exchangeText" -> {
                require(message.text.toByteArray().size <= 8000)
                require(message.action.matches(Regex("[a-f0-9-]{36}")))
                if (mutable.value.clipboard && mutable.value.peerClipboard) {
                    receivedClipboard = message.action
                    runCatching { platform.putText(message.text) }.onSuccess { notice("clipboardReceived") }.onFailure { receivedClipboard = ""; notice("clipboardFailed") }
                }
            }
            "exchangeOffer" -> {
                val offer = json.decodeFromString<FileOffer>(message.text).also(FileOffer::validate)
                require(item(offer.id) == null)
                if (offer.kind != "files" && (!mutable.value.clipboard || !mutable.value.peerClipboard)) { send(Message("exchangeCancel", text = offer.id)); return }
                if (mutable.value.items.count { it.incoming && (it.active || it.phase == TransferPhase.OFFERED) } >= 4 || incomingReserved + offer.bytes > FileOffer.MAX_BYTES) {
                    send(Message("exchangeCancel", text = offer.id)); notice("receiveLimit"); return
                }
                incomingReserved += offer.bytes
                add(TransferItem(offer, incoming = true))
                if (offer.kind != "files") receivedClipboard = offer.id
                if (offer.kind == "clipboard" && platform.virtualFileClipboard) {
                    if (!platform.putFiles(offer) { requestDownload(offer.id) }) { receivedClipboard = ""; notice("clipboardFailed") }
                } else if (offer.kind == "image" && platform.automaticClipboard) { clipboardImages.add(offer.id); receive(offer.id, clipboardImage = true) }
            }
            "exchangeRequest" -> {
                val link = json.decodeFromString<TransferLink>(message.text)
                val item = item(link.id) ?: return
                if (item.incoming || jobs.containsKey(link.id) || item.phase == TransferPhase.CANCELED) return
                if (jobs.size >= 2) { send(Message("exchangeFailure", text = link.id)); return }
                val files = sources[link.id] ?: return
                val current = generation
                val transport = newTransport()
                transports[link.id] = transport
                update(link.id) { it.copy(phase = TransferPhase.CONNECTING, error = "") }
                jobs[link.id] = scope.launch {
                    try {
                        withContext(Dispatchers.IO) { transport.send(item.offer, files, link, reporter(link.id, current, false)) }
                        if (current == generation) update(link.id) { it.copy(phase = TransferPhase.DONE, bytes = it.offer.bytes, bytesPerSecond = 0) }
                    } catch (_: CancellationException) { }
                    catch (_: Exception) { if (current == generation) fail(link.id) }
                    finally { transport.close(); transports.remove(link.id, transport); if (current == generation) jobs.remove(link.id) }
                }
            }
            "exchangeCancel" -> cancel(message.text, notifyPeer = false)
            "exchangeFailure" -> if (item(message.text)?.active == true) { transports.remove(message.text)?.close(); jobs.remove(message.text)?.cancel(); fail(message.text) }
        }
    }

    private fun newTransport() = TransferTransport(relayFactory, peerAddress ?: error("Disconnected"), viaRelay, congested)
    private fun item(id: String) = mutable.value.items.firstOrNull { it.offer.id == id }
    private fun add(item: TransferItem) {
        mutable.update { state ->
            val keep = state.items.filter { it.active || it.phase == TransferPhase.OFFERED }.map { it.offer.id }.toSet()
            val items = (state.items.filter { it.offer.id in keep } + state.items.filter { it.offer.id !in keep }.takeLast(3) + item).distinctBy { it.offer.id }
            state.copy(items = items)
        }
        val retained = mutable.value.items.map { it.offer.id }.toSet()
        sources.keys.filter { it !in retained }.forEach(sources::remove)
        downloads.keys.filter { it !in retained }.forEach(downloads::remove)
    }
    private fun update(id: String, change: (TransferItem) -> TransferItem) {
        mutable.update { state -> state.copy(items = state.items.map { if (it.offer.id == id) change(it).copy(updatedAt = System.currentTimeMillis()) else it }) }
    }
    private fun fail(id: String) {
        update(id) { if (it.phase == TransferPhase.CANCELED) it else it.copy(phase = TransferPhase.FAILED, error = "transferFailed", bytesPerSecond = 0) }
        downloads.remove(id)?.completeExceptionally(java.io.IOException("Transfer unavailable"))
    }
    private fun reporter(id: String, current: Long, incoming: Boolean): (Long, Boolean) -> Unit {
        var at = System.nanoTime(); var previous = 0L
        return { count, verifying ->
            val now = System.nanoTime()
            if (current == generation && (now - at > 200_000_000 || verifying)) {
                val rate = if (now > at) ((count - previous).coerceAtLeast(0) * 1e9 / (now - at)).toLong() else 0
                update(id) { it.copy(phase = if (verifying) TransferPhase.VERIFYING else if (incoming) TransferPhase.RECEIVING else TransferPhase.SENDING, bytes = count, bytesPerSecond = rate) }
                at = now; previous = count
            }
        }
    }

    private fun sessionRoot(current: Long): File = synchronized(rootLock) {
        check(current == generation)
        root?.let { return@synchronized it }
        val parent = platform!!.cacheRoot
        check(parent.mkdirs() || parent.isDirectory)
        java.nio.file.Files.createTempDirectory(parent.toPath(), "session-").toFile().also { root = it }
    }

    /** Called by Explorer's data object. The UI and media reader never wait for this future. */
    private fun requestDownload(id: String): CompletableFuture<File> {
        val current = generation
        val result = CompletableFuture<File>()
        scope.launch {
            if (current != generation || !mutable.value.connected) { result.completeExceptionally(java.io.IOException("Disconnected")); return@launch }
            val existing = downloads[id]
            if (existing != null) { existing.whenComplete { file, error -> if (error == null) result.complete(file) else result.completeExceptionally(error) }; return@launch }
            downloads[id] = result
            download(id, result, current)
        }
        return result
    }

    private fun download(id: String, result: CompletableFuture<File>, current: Long) {
        val item = item(id)
        if (item == null || !item.incoming || item.phase == TransferPhase.CANCELED || platform == null) { result.completeExceptionally(java.io.IOException("Unavailable")); downloads.remove(id); return }
        if (jobs.values.any { it.isActive }) { result.completeExceptionally(java.io.IOException("Busy")); downloads.remove(id); notice("busy"); return }
        val transport = newTransport()
        transports[id] = transport
        update(id) { it.copy(phase = TransferPhase.CONNECTING, error = "") }
        jobs[id] = scope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    val directory = File(sessionRoot(current), id)
                    check(directory.mkdirs() || directory.isDirectory)
                    require(directory.usableSpace > item.offer.bytes + 16 * 1024 * 1024)
                    transport.receive(item.offer, directory, { link ->
                        if (current != generation) error("Disconnected")
                        send(Message("exchangeRequest", text = json.encodeToString(link)))
                    }, reporter(id, current, true))
                    directory
                }
                if (current != generation) { result.completeExceptionally(java.io.IOException("Disconnected")); return@launch }
                update(id) { it.copy(phase = TransferPhase.DONE, bytes = it.offer.bytes, bytesPerSecond = 0) }
                result.complete(target)
            } catch (_: CancellationException) { result.completeExceptionally(java.io.IOException("Canceled")) }
            catch (_: Exception) {
                if (current == generation) { fail(id); send(Message("exchangeFailure", text = id)) }
                result.completeExceptionally(java.io.IOException("Transfer unavailable"))
            } finally { transport.close(); transports.remove(id, transport); if (current == generation) jobs.remove(id) }
        }
    }

    fun receive(id: String, clipboardImage: Boolean = false) {
        val current = generation
        val offer = item(id)?.takeIf { it.incoming && !it.active }?.offer ?: return
        requestDownload(id).whenComplete { directory, error ->
            if (current != generation || error != null || platform == null) { clipboardImages.remove(id); return@whenComplete }
            saveJobs[id] = scope.launch {
                try {
                    if (clipboardImage) {
                        if (mutable.value.clipboard && mutable.value.peerClipboard) {
                            val bytes = withContext(Dispatchers.IO) { File(directory, "Screenshot.png").readBytes() }
                            if (platform.putImage(bytes)) { platform.clipboardReady(); notice("clipboardReceived") } else notice("clipboardFailed")
                        }
                    } else {
                        update(id) { it.copy(phase = TransferPhase.SAVING) }
                        val location = platform.saveFiles(offer, directory)
                        if (current == generation) update(id) {
                            if (location == null) it.copy(phase = TransferPhase.FAILED, error = "saveCanceled")
                            else it.copy(phase = TransferPhase.DONE, location = location)
                        }
                    }
                } catch (_: CancellationException) { }
                catch (_: Exception) { if (current == generation) { update(id) { it.copy(phase = TransferPhase.FAILED, error = "saveFailed") } } }
                finally { clipboardImages.remove(id); saveJobs.remove(id) }
            }
        }
    }

    fun retry(id: String) {
        val item = item(id) ?: return
        if (!item.incoming) { notice("retryOtherDevice"); return }
        receive(id, clipboardImage = item.offer.kind == "image" && platform?.automaticClipboard == true)
    }
    fun open(id: String) { item(id)?.location?.takeIf { it.isNotEmpty() }?.let { runCatching { platform?.openSaved(it) }.onFailure { notice("openFailed") } } }
    fun cancel(id: String, notifyPeer: Boolean = true) {
        if (item(id) == null) return
        transports.remove(id)?.close(); jobs.remove(id)?.cancel()
        saveJobs.remove(id)?.cancel()
        downloads.remove(id)?.completeExceptionally(java.io.IOException("Canceled"))
        sources.remove(id)
        update(id) { it.copy(phase = TransferPhase.CANCELED, bytesPerSecond = 0) }
        if (notifyPeer) send(Message("exchangeCancel", text = id))
    }
    fun notice(key: String) {
        mutable.update { it.copy(notice = key) }
        noticeJob?.cancel()
        noticeJob = scope.launch { delay(5000); mutable.update { it.copy(notice = "") } }
    }
    fun dismissNotice() { noticeJob?.cancel(); mutable.update { it.copy(notice = "") } }

    fun stop() {
        generation++
        platform?.watchClipboard(null); platform?.clearRemoteClipboard()
        transports.values.forEach { it.close() }; transports.clear()
        val stopping = jobs.values.toList() + saveJobs.values.toList()
        stopping.forEach { it.cancel() }; jobs.clear(); saveJobs.clear()
        platform?.sessionEnded()
        downloads.values.forEach { it.completeExceptionally(java.io.IOException("Disconnected")) }; downloads.clear()
        sources.clear(); clipboardOffer = null; publishedClipboard = ""; receivedClipboard = ""; incomingReserved = 0; peerAddress = null; noticeJob?.cancel(); pasteWaiters.clear(); clipboardImages.clear()
        messagesAt = System.nanoTime(); messagesInWindow = 0; barriersInFlight = 0
        val cleanup = synchronized(rootLock) { root.also { root = null } }
        if (cleanup != null) scope.launch(Dispatchers.IO) {
            stopping.joinAll()
            // Only our generated, private session directory. Never follow links or touch saved destinations.
            runCatching { java.nio.file.Files.walkFileTree(cleanup.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult { java.nio.file.Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult { java.nio.file.Files.deleteIfExists(dir); return java.nio.file.FileVisitResult.CONTINUE }
            }) }
        }
        mutable.value = ExchangeState()
    }
    fun dispose() { stop() }
}
