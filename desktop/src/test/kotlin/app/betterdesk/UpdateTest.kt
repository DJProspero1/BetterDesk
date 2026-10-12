package app.betterdesk

import app.betterdesk.core.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.test.*

/** A bounded loopback release fixture; never contacts GitHub. */
class UpdateTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8)
    private val routes = mutableMapOf<String, Pair<Int, ByteArray>>()
    private val redirects = mutableMapOf<String, String>()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val folder = Files.createTempDirectory("betterdesk-update").toFile()
    private val base get() = "http://127.0.0.1:${server.address.port}/releases"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            redirects[path]?.let { exchange.responseHeaders.add("Location", it); exchange.sendResponseHeaders(302, -1); exchange.close(); return@createContext }
            val (status, body) = routes[path] ?: (404 to ByteArray(0))
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }
    @After fun cleanup() { server.stop(0); dispatcher.close(); folder.deleteRecursively() }

    private fun source() = UpdateSource(base, loopbackTest = true)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun publish(version: String, exe: ByteArray = "windows $version".toByteArray(), apk: ByteArray = "android $version".toByteArray(), code: Long = 20): UpdateManifest {
        val manifest = UpdateManifest(version,
            UpdateAsset("BetterDesk-Windows-$version.exe", sha(exe), exe.size.toLong()),
            UpdateAsset("BetterDesk-Android-$version-debug.apk", sha(apk), apk.size.toLong(), code))
        routes["/releases/latest/download/update.json"] = 200 to Json.encodeToString(manifest).toByteArray()
        routes["/releases/download/v$version/${manifest.windows.name}"] = 200 to exe
        routes["/releases/download/v$version/${manifest.android.name}"] = 200 to apk
        return manifest
    }

    @Test fun `versions compare numerically and never accept malformed input`() {
        assertTrue(UpdateSource.newer("0.10.0", "0.9.9"))
        assertTrue(UpdateSource.newer("1.0.0", "0.99.99"))
        assertFalse(UpdateSource.newer("0.7.1", "0.7.1"))
        assertFalse(UpdateSource.newer("0.7.0", "0.7.1"))
        assertFalse(UpdateSource.newer("0.8.0-beta", "0.7.1"))
        assertFalse(UpdateSource.newer("0.8.0", ""))
    }

    @Test fun `source is pinned to the public release origin`() {
        assertFailsWith<IllegalArgumentException> { UpdateSource("https://example.com/releases", loopbackTest = false) }
        assertFailsWith<IllegalArgumentException> { UpdateSource("http://localhost:1/releases", loopbackTest = true) }
        UpdateSource()
    }

    @Test fun `manifest names, digests and sizes are validated`() {
        val good = publish("0.8.0")
        assertEquals(good, source().latest())
        for (bad in listOf(
            good.copy(version = "0.8"),
            good.copy(windows = good.windows.copy(name = "../BetterDesk.exe")),
            good.copy(android = good.android.copy(name = "Other-0.8.0.apk")),
            good.copy(android = good.android.copy(versionCode = 0)),
            good.copy(windows = good.windows.copy(sha256 = "ABC")),
            good.copy(windows = good.windows.copy(size = 0)),
        )) assertFailsWith<IllegalArgumentException> { UpdateSource.validate(bad) }
        routes["/releases/latest/download/update.json"] = 200 to ByteArray(20_000) { ' '.code.toByte() }
        assertFailsWith<IllegalArgumentException> { source().latest() }
    }

    @Test fun `download keeps only a file with the exact size and digest`() {
        val manifest = publish("0.8.0")
        val destination = File(folder, "BetterDesk.exe.new")
        source().download(manifest, manifest.windows, destination) {}
        assertEquals("windows 0.8.0", destination.readText())

        destination.delete()
        routes["/releases/download/v0.8.0/${manifest.windows.name}"] = 200 to "windows 0.8.X".toByteArray()
        assertFailsWith<IOException> { source().download(manifest, manifest.windows, destination) {} }
        routes["/releases/download/v0.8.0/${manifest.windows.name}"] = 200 to "windows 0.8.0 and more".toByteArray()
        assertFailsWith<IOException> { source().download(manifest, manifest.windows, destination) {} }
        assertFalse(destination.exists())
        assertTrue(folder.listFiles()!!.isEmpty(), "Partial downloads must be removed")
        val foreign = UpdateAsset("BetterDesk-Windows-0.8.0.exe", sha("x".toByteArray()), 1)
        assertFailsWith<IllegalArgumentException> { source().download(manifest, foreign, destination) {} }
    }

    @Test fun `redirects may not leave the allowed origin`() {
        val manifest = publish("0.8.0")
        redirects["/releases/download/v0.8.0/${manifest.windows.name}"] = "https://example.com/BetterDesk.exe"
        assertFailsWith<IllegalArgumentException> { source().download(manifest, manifest.windows, File(folder, "a.exe")) {} }
        redirects["/releases/download/v0.8.0/${manifest.windows.name}"] = "/assets/windows"
        routes["/assets/windows"] = 200 to "windows 0.8.0".toByteArray()
        source().download(manifest, manifest.windows, File(folder, "b.exe")) {}
    }

    private class Platform(val folder: File, override val target: String = "windows", override val currentVersion: String = "0.7.1", override val currentVersionCode: Long = 9) : UpdatePlatform {
        var allowed = true
        val installed = mutableListOf<String>()
        override fun stagingFile(asset: UpdateAsset) = File(folder, asset.name + ".staged")
        override fun canInstall() = allowed
        override fun install(file: File, onResult: (Boolean) -> Unit) { installed += file.readText() }
    }

    private fun AppUpdater.await(stage: UpdateStage) = runBlocking { withTimeout(5000) { state.first { it.stage == stage } } }

    @Test fun `updater waits for consent and never installs during a connection`() {
        publish("0.8.0")
        val platform = Platform(folder)
        var idle = false
        val updater = AppUpdater(platform, { idle }, source(), firstCheckMs = 0, dispatcher = dispatcher)
        try {
            assertEquals("0.8.0", updater.await(UpdateStage.AVAILABLE).version)
            assertTrue(platform.installed.isEmpty(), "Checking must not download or install")
            runBlocking(dispatcher) { updater.update() }
            updater.await(UpdateStage.READY)
            assertTrue(platform.installed.isEmpty(), "A connection blocks installation")
            idle = true
            runBlocking(dispatcher) { updater.update() }
            updater.await(UpdateStage.INSTALLING)
            runBlocking { withTimeout(5000) { while (platform.installed.isEmpty()) delay(10) } }
            assertEquals(listOf("windows 0.8.0"), platform.installed)
        } finally { updater.dispose() }
    }

    @Test fun `older, equal and lower versionCode releases are not offered`() {
        publish("0.7.1")
        val same = AppUpdater(Platform(folder), { true }, source(), firstCheckMs = 60_000, dispatcher = dispatcher)
        publish("0.8.0", code = 9)
        val android = AppUpdater(Platform(folder, "android"), { true }, source(), firstCheckMs = 60_000, dispatcher = dispatcher)
        try {
            publish("0.7.1")
            runBlocking(dispatcher) { same.check() }
            assertEquals(UpdateStage.NONE, same.state.value.stage)
            publish("0.8.0", code = 9)
            runBlocking(dispatcher) { android.check() }
            assertEquals(UpdateStage.NONE, android.state.value.stage)
        } finally { same.dispose(); android.dispose() }
    }

    @Test fun `android asks for the install permission before downloading`() {
        publish("0.8.0")
        val platform = Platform(folder, "android").apply { allowed = false }
        val updater = AppUpdater(platform, { true }, source(), firstCheckMs = 60_000, dispatcher = dispatcher)
        try {
            runBlocking(dispatcher) { updater.check(); updater.update() }
            assertEquals(UpdateStage.PERMISSION, updater.state.value.stage)
            assertTrue(folder.listFiles()!!.isEmpty())
            platform.allowed = true
            runBlocking(dispatcher) { updater.refreshPermission() }
            runBlocking { withTimeout(5000) { while (platform.installed.isEmpty()) delay(10) } }
            assertEquals(listOf("android 0.8.0"), platform.installed)
        } finally { updater.dispose() }
    }

    @Test fun `a failed download keeps the current app and can be dismissed`() {
        val manifest = publish("0.8.0")
        routes.remove("/releases/download/v0.8.0/${manifest.windows.name}")
        val platform = Platform(folder)
        val updater = AppUpdater(platform, { true }, source(), firstCheckMs = 60_000, dispatcher = dispatcher)
        try {
            runBlocking(dispatcher) { updater.check(); updater.update() }
            updater.await(UpdateStage.FAILED)
            assertTrue(platform.installed.isEmpty())
            runBlocking(dispatcher) { updater.later(); updater.check() }
            assertEquals(UpdateStage.NONE, updater.state.value.stage, "Later hides this version until a newer one")
        } finally { updater.dispose() }
    }
}
