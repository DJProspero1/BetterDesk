package app.betterdesk.core

import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Platform handles stay local. Only FileEntry metadata crosses the connection. */
data class LocalFile(val entry: FileEntry, val open: () -> InputStream)
@Serializable data class FileEntry(val path: String, val size: Long, val directory: Boolean = false)
@Serializable data class FileOffer(val id: String, val entries: List<FileEntry>, val kind: String = "files") {
    val bytes: Long get() = entries.sumOf { it.size }
    val fileCount: Int get() = entries.count { !it.directory }
    fun validate() {
        require(id.matches(Regex("[a-f0-9]{32}")))
        require(kind in setOf("files", "clipboard", "image"))
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES)
        val paths = HashSet<String>()
        for (entry in entries) {
            require(validPath(entry.path) && entry.size in 0..MAX_BYTES && (!entry.directory || entry.size == 0L))
            require(paths.add(entry.path.lowercase(java.util.Locale.ROOT)))
        }
        require(fileCount > 0 && bytes <= MAX_BYTES)
        for (entry in entries) {
            val parts = entry.path.split('/')
            for (n in 1 until parts.size) {
                val parent = parts.take(n).joinToString("/")
                require(entries.none { it.path.equals(parent, true) && !it.directory })
            }
        }
        if (kind == "image") require(entries.size == 1 && bytes <= MAX_IMAGE_BYTES && entries[0].path == "Screenshot.png")
    }
    companion object {
        const val MAX_ENTRIES = 128
        const val MAX_BYTES = 1024L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        fun validPath(path: String): Boolean = path.length in 1..200 && path.toByteArray().size <= 600 &&
            path.split('/').all { part ->
                part.isNotBlank() && part !in setOf(".", "..") && !part.endsWith('.') && !part.endsWith(' ') &&
                    part.none { it in "\\:*?\"<>|" || Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() } &&
                    !part.substringBefore('.').uppercase(java.util.Locale.ROOT).matches(Regex("CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³]"))
            }
    }
}

sealed interface ClipboardValue {
    data class Unavailable(val reason: String) : ClipboardValue
    data class Text(val text: String) : ClipboardValue
    data class Image(val png: ByteArray) : ClipboardValue
    data class Files(val files: List<LocalFile>) : ClipboardValue
}

/** Cancel saving promptly as well as downloading. The caller owns/cleans its destination. */
suspend fun copyTransferFile(input: InputStream, output: java.io.OutputStream): Long {
    val buffer = ByteArray(65_536)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) return total
        output.write(buffer, 0, count); total += count
        require(total <= FileOffer.MAX_BYTES)
    }
}

/** No broad storage grant or arbitrary remote path is accepted by these APIs. */
interface ExchangePlatform {
    val cacheRoot: File
    val automaticClipboard: Boolean get() = false
    val virtualFileClipboard: Boolean get() = false
    suspend fun chooseFiles(): List<LocalFile>
    suspend fun saveFiles(offer: FileOffer, directory: File): String?
    fun openSaved(location: String)
    fun watchClipboard(onChange: ((ClipboardValue) -> Unit)?)
    fun readClipboard(): ClipboardValue?
    fun putText(text: String)
    fun putImage(png: ByteArray): Boolean = false
    fun putFiles(offer: FileOffer, fetch: () -> CompletableFuture<File>): Boolean = false
    fun clearRemoteClipboard()
    suspend fun clipboardReady() = Unit
    fun sessionEnded() = Unit
}

enum class TransferPhase { OFFERED, CONNECTING, SENDING, RECEIVING, VERIFYING, SAVING, DONE, CANCELED, FAILED }
data class TransferItem(
    val offer: FileOffer, val incoming: Boolean, val phase: TransferPhase = TransferPhase.OFFERED,
    val bytes: Long = 0, val bytesPerSecond: Long = 0, val error: String = "", val location: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    val active get() = phase in setOf(TransferPhase.CONNECTING, TransferPhase.SENDING, TransferPhase.RECEIVING, TransferPhase.VERIFYING, TransferPhase.SAVING)
}
data class ExchangeState(val connected: Boolean = false, val clipboard: Boolean = true,
    val peerClipboard: Boolean = true, val items: List<TransferItem> = emptyList(), val notice: String = "")

@Serializable internal data class TransferLink(val id: String, val fingerprint: String, val secret: String,
    val port: Int = 0, val ticket: String = "", val expires: Long)
