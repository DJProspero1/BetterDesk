package app.betterdesk

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.betterdesk.core.*
import kotlinx.coroutines.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume

class AndroidExchange(private val context: Context) : ExchangePlatform {
    override val cacheRoot = File(context.cacheDir, "transfers")
    private val resolver = context.contentResolver
    private var activity = WeakReference<Activity>(null)
    private var launcher: ((Intent) -> Unit)? = null
    private var pending: CancellableContinuation<Intent?>? = null
    private var ownText: String? = null
    private val saved = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val selectedCache = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
    fun attach(activity: Activity, launcher: (Intent) -> Unit) { this.activity = WeakReference(activity); this.launcher = launcher }
    fun detach(activity: Activity) { if (this.activity.get() === activity) { this.activity.clear(); launcher = null } }
    fun result(code: Int, intent: Intent?) { pending?.let { if (it.isActive) it.resume(if (code == Activity.RESULT_OK) intent else null) }; pending = null }
    private suspend fun pick(intent: Intent): Intent? = suspendCancellableCoroutine { continuation ->
        check(pending == null)
        val launch = launcher
        if (launch == null) { continuation.resume(null); return@suspendCancellableCoroutine }
        pending = continuation
        continuation.invokeOnCancellation { pending = null }
        try { launch(intent) } catch (_: Exception) { pending = null; continuation.resume(null) }
    }
    override suspend fun chooseFiles(): List<LocalFile> {
        val result = pick(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }) ?: return emptyList()
        val uris = (result.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } } ?: listOfNotNull(result.data)).distinct()
        return withContext(Dispatchers.IO) { describe(uris) }
    }
    fun describe(uris: List<Uri>): List<LocalFile> {
        require(uris.size in 1..FileOffer.MAX_ENTRIES)
        val names = HashSet<String>()
        var total = 0L
        return uris.map { uri ->
            require(uri.scheme == "content")
            var name: String? = null; var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
            val safeName = name ?: "File"
            require(FileOffer.validPath(safeName) && '/' !in safeName && names.add(safeName.lowercase(java.util.Locale.ROOT)))
            // Cloud document providers may not know the size until read. Materialize only that user-selected item.
            var local: File? = null
            if (size < 0) {
                check(cacheRoot.mkdirs() || cacheRoot.isDirectory)
                local = File.createTempFile("selected-", ".part", cacheRoot)
                selectedCache.add(local)
                try {
                    size = 0
                    (resolver.openInputStream(uri) ?: error("File unavailable")).use { input ->
                        local.outputStream().use { output ->
                            val buffer = ByteArray(65_536)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                size += count; require(size + total <= FileOffer.MAX_BYTES)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                } catch (error: Exception) { local.delete(); throw error }
            }
            require(size in 0..FileOffer.MAX_BYTES)
            total += size; require(total <= FileOffer.MAX_BYTES)
            LocalFile(FileEntry(safeName, size)) { local?.inputStream() ?: resolver.openInputStream(uri) ?: error("File unavailable") }
        }
    }
    override suspend fun saveFiles(offer: FileOffer, directory: File): String? {
        val folder = "Received-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())}-${UUID.randomUUID().toString().take(6)}"
        return if (Build.VERSION.SDK_INT >= 29) withContext(Dispatchers.IO) {
            val inserted = ArrayList<Uri>()
            try {
                for (entry in offer.entries.filter { !it.directory }) {
                    val parent = entry.path.substringBeforeLast('/', "")
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, entry.path.substringAfterLast('/'))
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BetterDesk/$folder/" + if (parent.isEmpty()) "" else "$parent/")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Save failed")
                    inserted += uri
                    File(directory, entry.path).inputStream().use { input ->
                        (resolver.openOutputStream(uri, "w") ?: error("Save failed")).use { require(copyTransferFile(input, it) == entry.size) }
                    }
                }
                inserted.forEach { resolver.update(it, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
                "downloads".also(saved::add)
            } catch (error: Exception) { inserted.forEach { runCatching { resolver.delete(it, null, null) } }; throw error }
        } else {
            val tree = pick(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))?.data ?: return null
            withContext(Dispatchers.IO) {
                val treeRoot = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val batch = DocumentsContract.createDocument(resolver, treeRoot, DocumentsContract.Document.MIME_TYPE_DIR, folder) ?: error("Save failed")
                try {
                    val directories = hashMapOf("" to batch)
                    fun folderFor(path: String): Uri = directories.getOrPut(path) {
                        val parent = folderFor(path.substringBeforeLast('/', ""))
                        DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, path.substringAfterLast('/')) ?: error("Save failed")
                    }
                    for (entry in offer.entries) {
                        if (entry.directory) { folderFor(entry.path); continue }
                        val parent = folderFor(entry.path.substringBeforeLast('/', ""))
                        val uri = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", entry.path.substringAfterLast('/')) ?: error("Save failed")
                        File(directory, entry.path).inputStream().use { input ->
                            (resolver.openOutputStream(uri, "w") ?: error("Save failed")).use { require(copyTransferFile(input, it) == entry.size) }
                        }
                    }
                    tree.toString().also(saved::add)
                } catch (error: Exception) { runCatching { DocumentsContract.deleteDocument(resolver, batch) }; throw error }
            }
        }
    }
    override fun openSaved(location: String) {
        require(location in saved)
        val intent = if (location == "downloads") Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
        else Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(location), DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        (activity.get() ?: error("Open BetterDesk first")).startActivity(intent)
    }
    override fun watchClipboard(onChange: ((ClipboardValue) -> Unit)?) = Unit // Android forbids background clipboard monitoring.
    override fun readClipboard(): ClipboardValue? {
        if (activity.get()?.hasWindowFocus() != true) return null
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        return item.text?.toString()?.let { ClipboardValue.Text(it) }
            ?: item.uri?.takeIf { it.scheme == "content" }?.let { ClipboardValue.Files(describe(listOf(it))) }
    }
    override fun putText(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BetterDesk", text)); ownText = text
    }
    override fun clearRemoteClipboard() {
        // Never clear the person's newer clipboard. Android may deny reads when backgrounded.
        if (activity.get()?.hasWindowFocus() == true) runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (ownText != null && clipboard.primaryClip?.description?.label == "BetterDesk" && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == ownText) {
                if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
        ownText = null
    }
    override fun sessionEnded() {
        pending?.cancel(); pending = null
        val files = selectedCache.toList(); selectedCache.clear()
        CoroutineScope(Dispatchers.IO).launch { files.forEach { runCatching { it.delete() } } }
    }
}
