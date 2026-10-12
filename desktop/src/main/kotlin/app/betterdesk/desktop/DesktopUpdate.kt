package app.betterdesk.desktop

import app.betterdesk.core.UpdateAsset
import app.betterdesk.core.UpdatePlatform
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces the portable EXE the person opened, then reopens it. The launcher passes
 * its own path; development runs and other systems have none and are never updated.
 */
class DesktopUpdate(private val exit: () -> Unit) : UpdatePlatform {
    override val target = "windows"
    override val currentVersion: String = System.getProperty("betterdesk.version", "")
    private val portable: File? = System.getenv("BETTERDESK_PORTABLE")
        ?.let(::File)?.takeIf { System.getProperty("os.name").startsWith("Windows") && it.isFile && it.name.endsWith(".exe", true) }
    val available get() = portable != null && currentVersion.isNotEmpty()

    override fun stagingFile(asset: UpdateAsset): File {
        // Same folder as the EXE, so the final replacement is a rename on one volume.
        val current = portable ?: throw IOException("Not a portable build")
        return File(current.parentFile, current.name + ".new")
    }

    override fun install(file: File, onResult: (Boolean) -> Unit) {
        val current = portable ?: throw IOException("Not a portable build")
        val previous = File(current.parentFile, current.name + ".old")
        previous.delete()
        // The launcher has exited, so its file is free. Keep it until the new one is in place.
        Files.move(current.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
        try { Files.move(file.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        catch (e: Exception) { Files.move(previous.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE); throw e }
        ProcessBuilder(current.absolutePath, "--after", ProcessHandle.current().pid().toString())
            .directory(current.parentFile).start()
        exit()
    }
}
