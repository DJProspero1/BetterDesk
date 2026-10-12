package app.betterdesk.core

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

@Serializable data class UpdateAsset(val name: String, val sha256: String, val size: Long, val versionCode: Long = 0)
@Serializable data class UpdateManifest(val version: String, val windows: UpdateAsset, val android: UpdateAsset)

/** Platform installation. Installing always follows an explicit user action. */
interface UpdatePlatform {
    /** "windows" or "android": selects the manifest asset. */
    val target: String
    val currentVersion: String
    val currentVersionCode: Long get() = 0
    /** A writable file for the verified download, on the same volume as its destination. */
    fun stagingFile(asset: UpdateAsset): File
    /** False when Android still needs the user to allow installs from BetterDesk. */
    fun canInstall(): Boolean = true
    fun openInstallSettings() = Unit
    /** Hands the verified file to the OS; reports a later failure or cancellation through [onResult]. */
    fun install(file: File, onResult: (Boolean) -> Unit)
}

enum class UpdateStage { NONE, AVAILABLE, DOWNLOADING, PERMISSION, READY, INSTALLING, FAILED }
data class UpdateState(val stage: UpdateStage = UpdateStage.NONE, val version: String = "", val progress: Float = 0f)

/**
 * Releases come from the public GitHub repository, like other open-source desktop
 * apps: no credentials, a fixed origin, HTTPS, an exact size and SHA-256 per asset.
 * Android additionally refuses an APK signed by a different key.
 */
class UpdateSource internal constructor(private val base: String, private val loopbackTest: Boolean) {
    constructor() : this(RELEASES, false)
    private val json = Json { ignoreUnknownKeys = true }
    init { require(base == RELEASES || (loopbackTest && base.startsWith("http://127.0.0.1:"))) }

    fun latest(): UpdateManifest = json.decodeFromString<UpdateManifest>(String(fetch("$base/latest/download/update.json", MANIFEST_BYTES), Charsets.UTF_8)).also(::validate)

    /** Writes [asset] to [destination] only after its size and digest match. */
    fun download(manifest: UpdateManifest, asset: UpdateAsset, destination: File, onProgress: (Float) -> Unit) {
        require(asset == manifest.windows || asset == manifest.android)
        val partial = File(destination.parentFile, destination.name + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = open("$base/download/v${manifest.version}/${asset.name}")
            try {
                val length = connection.contentLengthLong
                if (length >= 0 && length != asset.size) throw IOException("Unexpected update size")
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(65_536); var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        if (total > asset.size) throw IOException("Unexpected update size")
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        onProgress(total.toFloat() / asset.size)
                    }
                    if (total != asset.size) throw IOException("Incomplete update")
                } }
            } finally { connection.disconnect() }
            if (!TlsIdentity.constantEquals(digest.digest().joinToString("") { "%02x".format(it) }, asset.sha256)) throw IOException("Update checksum mismatch")
            destination.delete()
            if (!partial.renameTo(destination)) throw IOException("Could not stage update")
        } finally { partial.delete() }
    }

    private fun fetch(url: String, limit: Int): ByteArray {
        val connection = open(url)
        try {
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(4096)
                while (true) { val n = input.read(chunk); if (n < 0) break; require(out.size() + n <= limit); out.write(chunk, 0, n) }
                out.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    /** Follows GitHub's release redirects only to GitHub-owned HTTPS hosts. */
    private fun open(start: String): HttpURLConnection {
        var url = start
        repeat(6) {
            val uri = URI(url)
            require(uri.userInfo == null && allowedHost(uri))
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000; connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "BetterDesk-Updater")
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                url = uri.resolve(location ?: throw IOException("Bad redirect")).toString()
                return@repeat
            }
            if (status != 200) { connection.disconnect(); throw IOException("Update service returned $status") }
            return connection
        }
        throw IOException("Too many redirects")
    }

    private fun allowedHost(uri: URI): Boolean {
        if (loopbackTest) return uri.scheme == "http" && uri.host == "127.0.0.1"
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && (host == "github.com" || host.endsWith(".githubusercontent.com"))
    }

    companion object {
        const val RELEASES = "https://github.com/DJProspero1/BetterDesk/releases"
        private const val MANIFEST_BYTES = 16_384
        private const val MAX_ASSET_BYTES = 512L * 1024 * 1024
        private val VERSION = Regex("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")
        private val DIGEST = Regex("[0-9a-f]{64}")

        fun validate(manifest: UpdateManifest) {
            require(manifest.version.matches(VERSION))
            require(manifest.windows.name == "BetterDesk-Windows-${manifest.version}.exe")
            require(manifest.android.name.matches(Regex("BetterDesk-Android-${Regex.escape(manifest.version)}(-debug)?\\.apk")))
            require(manifest.android.versionCode > 0)
            for (asset in listOf(manifest.windows, manifest.android)) require(asset.sha256.matches(DIGEST) && asset.size in 1..MAX_ASSET_BYTES)
        }

        /** Numeric X.Y.Z comparison; anything unparsable is never treated as newer. */
        fun newer(candidate: String, current: String): Boolean {
            if (!candidate.matches(VERSION) || !current.matches(VERSION)) return false
            val a = candidate.split('.').map(String::toInt); val b = current.split('.').map(String::toInt)
            val (x, y) = a.zip(b).firstOrNull { (x, y) -> x != y } ?: return false
            return x > y
        }
    }
}

/** Checks quietly; downloading and installing wait for the person to choose Update. */
class AppUpdater(
    private val platform: UpdatePlatform,
    /** False during a pending or active connection: installing restarts the app. */
    private val idle: () -> Boolean,
    private val source: UpdateSource = UpdateSource(),
    private val firstCheckMs: Long = 15_000,
    private val intervalMs: Long = 6 * 60 * 60_000L,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var manifest: UpdateManifest? = null
    private var staged: File? = null
    private var dismissed = ""

    init {
        scope.launch {
            delay(firstCheckMs)
            while (isActive) { check(); delay(intervalMs) }
        }
    }

    private fun asset(found: UpdateManifest) = if (platform.target == "android") found.android else found.windows
    private fun quiet() = mutable.value.stage in setOf(UpdateStage.NONE, UpdateStage.AVAILABLE)

    suspend fun check() {
        if (!quiet()) return
        val found = try { withContext(Dispatchers.IO) { source.latest() } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return }
        val newer = UpdateSource.newer(found.version, platform.currentVersion) &&
            (platform.target != "android" || found.android.versionCode > platform.currentVersionCode)
        if (!quiet()) return
        manifest = found.takeIf { newer }
        mutable.value = if (newer && found.version != dismissed) UpdateState(UpdateStage.AVAILABLE, found.version) else UpdateState()
    }

    fun later() {
        if (mutable.value.stage !in setOf(UpdateStage.AVAILABLE, UpdateStage.PERMISSION, UpdateStage.FAILED)) return
        dismissed = mutable.value.version; mutable.value = UpdateState()
    }

    fun update() {
        val found = manifest ?: return
        if (mutable.value.stage == UpdateStage.READY) { staged?.let { install(found, it) }; return }
        if (mutable.value.stage !in setOf(UpdateStage.AVAILABLE, UpdateStage.FAILED, UpdateStage.PERMISSION)) return
        if (!platform.canInstall()) { mutable.value = UpdateState(UpdateStage.PERMISSION, found.version); return }
        val asset = asset(found)
        mutable.value = UpdateState(UpdateStage.DOWNLOADING, found.version)
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    platform.stagingFile(asset).also { file ->
                        runInterruptible { source.download(found, asset, file) { progress -> mutable.value = mutable.value.copy(progress = progress.coerceIn(0f, 1f)) } }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.value = UpdateState(UpdateStage.FAILED, found.version); return@launch }
            staged = file
            install(found, file)
        }
    }

    // A connection may have started while downloading: never restart under it.
    private fun install(found: UpdateManifest, file: File) {
        if (!idle()) { mutable.value = UpdateState(UpdateStage.READY, found.version, 1f); return }
        mutable.value = UpdateState(UpdateStage.INSTALLING, found.version, 1f)
        val failed = { staged = null; mutable.value = UpdateState(UpdateStage.FAILED, found.version) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    platform.install(file) { installed -> scope.launch { if (!installed && mutable.value.stage == UpdateStage.INSTALLING) failed() } }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed() }
        }
    }

    fun openInstallSettings() = platform.openInstallSettings()

    /** Rechecks the real Android permission when the person returns to BetterDesk. */
    fun refreshPermission() { if (mutable.value.stage == UpdateStage.PERMISSION && platform.canInstall()) update() }

    fun dispose() { scope.cancel() }
}
