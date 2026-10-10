package app.betterdesk.desktop

import app.betterdesk.core.*
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFileChooser

class DesktopExchange : ExchangePlatform {
    override val cacheRoot = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "BetterDesk/transfers")
    override val automaticClipboard = true
    override val virtualFileClipboard = true
    private interface ClipboardApi : StdCallLibrary { fun GetClipboardSequenceNumber(): Int }
    private val api = Native.load("user32", ClipboardApi::class.java)
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "BetterDesk clipboard").apply { isDaemon = true } }
    @Volatile private var callback: ((ClipboardValue) -> Unit)? = null
    @Volatile private var ownedSequence = -1
    private var seenSequence = 0
    private val writes = java.util.concurrent.atomic.AtomicInteger()
    private val nativeFilesDelegate = lazy { WindowsFileClipboard { ownedSequence = api.GetClipboardSequenceNumber() } }
    private val nativeFiles by nativeFilesDelegate
    @Volatile private var clipboardWrite = CompletableFuture.completedFuture(Unit)
    private val saved = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    init {
        worker.scheduleWithFixedDelay({
            val listener = callback ?: return@scheduleWithFixedDelay
            if (writes.get() > 0) return@scheduleWithFixedDelay
            val sequence = api.GetClipboardSequenceNumber()
            if (sequence == seenSequence || sequence == ownedSequence) { seenSequence = sequence; return@scheduleWithFixedDelay }
            runCatching { readClipboard() }.onSuccess { value ->
                seenSequence = sequence
                if (callback === listener && value != null && sequence != ownedSequence) listener(value)
            }.onFailure { error ->
                if (error !is IllegalStateException) {
                    seenSequence = sequence
                    if (callback === listener) listener(ClipboardValue.Unavailable("selectionFailed"))
                }
            }
        }, 300, 300, TimeUnit.MILLISECONDS)
    }
    override fun watchClipboard(onChange: ((ClipboardValue) -> Unit)?) {
        callback = onChange
        seenSequence = api.GetClipboardSequenceNumber() // Do not export pre-session clipboard content.
    }
    override suspend fun chooseFiles(): List<LocalFile> {
        val selection = withContext(Dispatchers.Main) {
            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = true; fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
                dialogTitle = "BetterDesk"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList() else emptyList()
        }
        return withContext(Dispatchers.IO) { describe(selection) }
    }
    fun describe(files: List<File>): List<LocalFile> {
        val result = ArrayList<LocalFile>()
        fun add(file: File, path: String) {
            require(result.size < FileOffer.MAX_ENTRIES && FileOffer.validPath(path))
            val attrs = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            // Reject symlinks, junctions and nonregular device objects.
            require(!attrs.isSymbolicLink && !attrs.isOther)
            if (attrs.isDirectory) {
                result += LocalFile(FileEntry(path, 0, true)) { error("Directory") }
                val children = file.listFiles() ?: error("Folder unavailable")
                require(children.size <= FileOffer.MAX_ENTRIES)
                children.sortedBy { it.name }.forEach { add(it, "$path/${it.name}") }
            } else {
                require(attrs.isRegularFile && attrs.size() <= FileOffer.MAX_BYTES)
                val original = file.canonicalFile
                result += LocalFile(FileEntry(path, attrs.size())) {
                    val now = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    require(now.isRegularFile && !now.isSymbolicLink && !now.isOther && file.canonicalFile == original && now.size() == attrs.size())
                    Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                }
            }
            require(result.sumOf { it.entry.size } <= FileOffer.MAX_BYTES)
        }
        files.forEach { add(it, it.name) }
        return result
    }
    override fun readClipboard(): ClipboardValue? {
        if (writes.get() > 0 || api.GetClipboardSequenceNumber() == ownedSequence) return null
        val data = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null
        return when {
            data.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                @Suppress("UNCHECKED_CAST") val files = data.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                ClipboardValue.Files(describe(files))
            }
            data.isDataFlavorSupported(DataFlavor.imageFlavor) && !data.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
                val source = data.getTransferData(DataFlavor.imageFlavor) as java.awt.Image
                val width = source.getWidth(null); val height = source.getHeight(null)
                require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_388_608)
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                image.createGraphics().let { graphics -> try { graphics.drawImage(source, 0, 0, null) } finally { graphics.dispose() } }
                val bytes = ByteArrayOutputStream().use { output -> ImageIO.write(image, "png", output); output.toByteArray() }
                require(bytes.size <= FileOffer.MAX_IMAGE_BYTES)
                ClipboardValue.Image(bytes)
            }
            data.isDataFlavorSupported(DataFlavor.stringFlavor) -> ClipboardValue.Text((data.getTransferData(DataFlavor.stringFlavor) as String).also { require(it.toByteArray().size <= 8000) })
            else -> null
        }
    }
    private fun write(data: Transferable) {
        val completed = CompletableFuture<Unit>(); clipboardWrite = completed
        writes.incrementAndGet()
        worker.execute {
            try { Toolkit.getDefaultToolkit().systemClipboard.setContents(data, null); ownedSequence = api.GetClipboardSequenceNumber(); completed.complete(Unit) }
            catch (error: Exception) { completed.completeExceptionally(error) }
            finally { writes.decrementAndGet() }
        }
    }
    override fun putText(text: String) = write(StringSelection(text))
    override fun putImage(png: ByteArray): Boolean {
        require(png.size <= FileOffer.MAX_IMAGE_BYTES)
        val image = ImageIO.createImageInputStream(png.inputStream()).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            require(readers.hasNext())
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_388_608)
                reader.read(0)
            } finally { reader.dispose() }
        }
        write(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any { if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor); return image }
        })
        return true
    }
    override fun putFiles(offer: FileOffer, fetch: () -> CompletableFuture<File>): Boolean {
        writes.incrementAndGet()
        return try { clipboardWrite = nativeFiles.offer(offer, fetch).whenComplete { _, _ -> writes.decrementAndGet() }; true }
        catch (_: Exception) { writes.decrementAndGet(); false }
    }
    override suspend fun clipboardReady() { withContext(Dispatchers.IO) { clipboardWrite.get(12, TimeUnit.SECONDS) } }
    override fun clearRemoteClipboard() {
        if (nativeFilesDelegate.isInitialized()) nativeFiles.clear()
        worker.execute {
            if (api.GetClipboardSequenceNumber() == ownedSequence) runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(""), null) }
            ownedSequence = -1
        }
    }
    override suspend fun saveFiles(offer: FileOffer, directory: File): String = withContext(Dispatchers.IO) {
        val downloads = File(System.getProperty("user.home"), "Downloads/BetterDesk")
        check(downloads.mkdirs() || downloads.isDirectory)
        val destination = Files.createTempDirectory(downloads.toPath(), "Received-").toFile()
        try {
        for (entry in offer.entries) {
            val file = File(destination, entry.path)
            require(file.canonicalPath.startsWith(destination.canonicalPath + File.separator))
            if (entry.directory) check(file.mkdirs() || file.isDirectory)
            else {
                check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
                File(directory, entry.path).inputStream().use { input ->
                    Files.newOutputStream(file.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { output ->
                        require(copyTransferFile(input, output) == entry.size)
                    }
                }
            }
        }
        destination.absolutePath.also(saved::add)
        } catch (error: Exception) {
            // Only the fresh batch directory created above; preserve every existing user file.
            if (destination.parentFile.canonicalFile == downloads.canonicalFile) runCatching {
                Files.walkFileTree(destination.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult { Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE }
                    override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult { Files.deleteIfExists(dir); return java.nio.file.FileVisitResult.CONTINUE }
                })
            }
            throw error
        }
    }
    override fun openSaved(location: String) {
        require(location in saved)
        worker.execute { runCatching { Desktop.getDesktop().open(File(location)) } }
    }
}
