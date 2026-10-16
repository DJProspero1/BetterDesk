package app.betterdesk.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage
import app.betterdesk.core.*
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.IntByReference
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DesktopPlatform : RemotePlatform {
    override val exchange = DesktopExchange()
    override val receivesAudio = true
    override val reduceMotion: Boolean get() = runCatching { java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("win.clientAreaAnimation") == false }.getOrDefault(false)
    @Volatile private var audioCapture: WasapiAudio? = null
    private val audioPlayer = DesktopAudioPlayer()
    override fun startAudio(onAudio: (Packet.Audio) -> Unit): Boolean {
        stopAudioCapture()
        val capture = WasapiAudio(); audioCapture = capture
        if (!active.get()) { capture.stop(); return false }
        return capture.start(onAudio).also { if (!active.get()) capture.stop() }
    }
    override fun stopAudioCapture() { audioCapture?.stop(); audioCapture = null }
    override fun playAudio(audio: Packet.Audio) = audioPlayer.play(audio)
    override fun startAudioPlayback() = audioPlayer.start()
    override fun stopAudioPlayback() = audioPlayer.stop()
    private val preferences by lazy { java.util.prefs.Preferences.userRoot().node("app/betterdesk") }
    override val deviceIdentity: DeviceIdentity? by lazy { runCatching { DesktopDeviceIdentity(preferences) }.getOrNull() }
    override var rememberedDevices: String?
        get() = runCatching { preferences.get("rememberedDevices", null)?.let(DesktopDeviceIdentity::unprotect) }.getOrNull()
        set(value) {
            if (value == null) preferences.remove("rememberedDevices") else preferences.put("rememberedDevices", DesktopDeviceIdentity.protect(value))
            preferences.flush()
        }
    override var languageTag: String?
        get() = runCatching { preferences.get("language", null) }.getOrNull()?.takeIf { it == "en" || it == "pt" }
        set(value) { if (value == null) preferences.remove("language") else preferences.put("language", value) }
    override val isAndroid = false
    override val preferredIncomingCodec = "avc"
    private val decodeLock = Any()
    private var avcDecoder: AvcDecoder? = null
    override val defaultName = System.getProperty("user.name", "Helper")
    private val active = AtomicBoolean(false)
    private var capture: java.util.concurrent.ScheduledExecutorService? = null
    private var robot: Robot? = null
    private var bounds = Rectangle()
    private var sequence = 0L
    @Volatile var captureBackend: String = "Not started"
        private set
    @Volatile private var capturePollAt = 0L
    override fun captureResponsive() = active.get() && System.nanoTime() - capturePollAt < 2_000_000_000
    override fun controlAvailable() = System.getProperty("os.name").startsWith("Windows")
    override fun openControlSettings() = Unit

    override suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit): Boolean {
        stopSharing()
        return try {
            val display = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            bounds = display.defaultConfiguration.bounds
            robot = Robot(display)
            active.set(true); sequence = 0
            capture = Executors.newSingleThreadScheduledExecutor { Thread(it, "BetterDesk screen").apply { isDaemon = true } }.also { executor ->
                val running = AtomicBoolean(true)
                val scale = minOf(1.0, 1440.0 / maxOf(bounds.width, bounds.height))
                var native: NativeCapture? = null
                executor.scheduleAtFixedRate({
                    if (!running.get()) return@scheduleAtFixedRate
                    try {
                        val target = native ?: runCatching<NativeCapture> { DxgiCapture(bounds, (bounds.width * scale).toInt(), (bounds.height * scale).toInt()) }
                            .getOrElse { GdiCapture(bounds, (bounds.width * scale).toInt(), (bounds.height * scale).toInt()) }
                            .also { native = it; captureBackend = it.javaClass.simpleName }
                        val bytes = target.jpeg()
                        capturePollAt = System.nanoTime()
                        if (bytes == null) return@scheduleAtFixedRate
                        if (running.get()) onFrame(Packet.Frame(++sequence, target.width, target.height, bytes))
                    } catch (_: Exception) { if (running.getAndSet(false)) { active.set(false); onStopped() } }
                }, 0, 8_333_333, TimeUnit.NANOSECONDS)
                // Dispose native codec resources on the capture thread after its last task.
                captureCleanup = { running.set(false); executor.execute { native?.close() } }
            }
            true
        } catch (_: Exception) { stopSharing(); false }
    }

    override fun stopSharing() {
        active.set(false); runCatching { captureCleanup?.invoke() }; captureCleanup = null
        capture?.shutdown(); capture = null
        runCatching { robot?.mouseRelease(InputEvent.BUTTON1_DOWN_MASK or InputEvent.BUTTON3_DOWN_MASK) }
        synchronized(decodeLock) { avcDecoder?.close(); avcDecoder = null }
    }

    private var captureCleanup: (() -> Unit)? = null

    private fun isOurForeground(): Boolean {
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(User32.INSTANCE.GetForegroundWindow(), pid)
        return pid.value == Kernel32.INSTANCE.GetCurrentProcessId()
    }

    override fun applyInput(input: Message) { submitInput(input) }
    override fun submitInput(input: Message): Boolean {
        if (!active.get() || !input.isValidInput()) return false
        val r = robot ?: return false
        val x = bounds.x + (input.x * (bounds.width - 1)).toInt()
        val y = bounds.y + (input.y * (bounds.height - 1)).toInt()
        if (input.action in setOf("tap", "right", "swipe", "scrollUp", "scrollDown")) {
            // A helper cannot press this app's own consent/stop controls.
            if (Window.getWindows().any { it.isVisible && it.bounds.contains(x, y) }) return false
        } else if (isOurForeground()) return false
        when (input.action) {
            "tap", "right" -> {
                r.mouseMove(x, y)
                val button = if (input.action == "right") InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
                try { r.mousePress(button) } finally { r.mouseRelease(button) }
            }
            "swipe" -> {
                val endX = bounds.x + (input.endX * (bounds.width - 1)).toInt()
                val endY = bounds.y + (input.endY * (bounds.height - 1)).toInt()
                if (Window.getWindows().any { it.isVisible && it.bounds.intersectsLine(x.toDouble(), y.toDouble(), endX.toDouble(), endY.toDouble()) }) return false
                r.mouseMove(x, y)
                try { r.mousePress(InputEvent.BUTTON1_DOWN_MASK); r.mouseMove(endX, endY) } finally { r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
            }
            "scrollUp", "scrollDown" -> { r.mouseMove(x, y); r.mouseWheel(if (input.action == "scrollUp") -3 else 3) }
            "enter" -> press(KeyEvent.VK_ENTER)
            "backspace" -> press(KeyEvent.VK_BACK_SPACE)
            "back" -> press(KeyEvent.VK_ESCAPE)
            "home" -> press(KeyEvent.VK_HOME)
            "copy", "cut", "paste", "selectAll" -> chord(when (input.action) { "copy" -> KeyEvent.VK_C; "cut" -> KeyEvent.VK_X; "paste" -> KeyEvent.VK_V; else -> KeyEvent.VK_A }, app.betterdesk.core.RemoteKeys.CTRL or input.modifiers)
            "key" -> chord(desktopKey(input.key) ?: return false, input.modifiers)
            "text" -> input.text.forEach { char ->
                if (active.get() && !isOurForeground()) {
                    when (char) {
                        '\n' -> press(KeyEvent.VK_ENTER)
                        '\r' -> Unit // CRLF is one Enter.
                        '\t' -> press(KeyEvent.VK_TAB)
                        else -> { unicode(char, false); unicode(char, true) }
                    }
                }
            }
        }
        return true
    }

    private fun chord(key: Int, modifiers: Int) {
        val r = robot ?: return
        val held = ArrayList<Int>(3)
        try {
            for ((mask, code) in listOf(1 to KeyEvent.VK_SHIFT, 2 to KeyEvent.VK_CONTROL, 4 to KeyEvent.VK_ALT)) {
                if (modifiers and mask != 0) { r.keyPress(code); held += code }
            }
            if (active.get() && !isOurForeground()) press(key)
        } finally { held.asReversed().forEach { r.keyRelease(it) } }
    }

    private fun desktopKey(key: String): Int? = when {
        key.length == 1 && key[0] in 'A'..'Z' -> KeyEvent.VK_A + (key[0] - 'A')
        key.length == 1 && key[0] in '0'..'9' -> KeyEvent.VK_0 + (key[0] - '0')
        key.matches(Regex("F([1-9]|1[0-2])")) -> KeyEvent.VK_F1 + key.drop(1).toInt() - 1
        else -> mapOf("Enter" to KeyEvent.VK_ENTER, "Tab" to KeyEvent.VK_TAB, "Backspace" to KeyEvent.VK_BACK_SPACE,
            "Delete" to KeyEvent.VK_DELETE, "Escape" to KeyEvent.VK_ESCAPE, "Left" to KeyEvent.VK_LEFT, "Right" to KeyEvent.VK_RIGHT,
            "Up" to KeyEvent.VK_UP, "Down" to KeyEvent.VK_DOWN, "Home" to KeyEvent.VK_HOME, "End" to KeyEvent.VK_END,
            "PageUp" to KeyEvent.VK_PAGE_UP, "PageDown" to KeyEvent.VK_PAGE_DOWN, "Insert" to KeyEvent.VK_INSERT, "Space" to KeyEvent.VK_SPACE)[key]
    }

    private fun press(key: Int) { try { robot?.keyPress(key) } finally { robot?.keyRelease(key) } }
    private fun unicode(char: Char, up: Boolean) {
        val event = WinUser.INPUT()
        event.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
        event.input.setType("ki")
        event.input.ki.wVk = WinDef.WORD(0)
        event.input.ki.wScan = WinDef.WORD(char.code.toLong())
        event.input.ki.dwFlags = WinDef.DWORD((0x0004 or if (up) 0x0002 else 0).toLong())
        event.write()
        User32.INSTANCE.SendInput(WinDef.DWORD(1), arrayOf(event), event.size())
    }

    override fun decode(frame: Packet.Frame): ImageBitmap? = runCatching {
        if (frame.codec.startsWith("avc")) synchronized(decodeLock) {
            (avcDecoder ?: AvcDecoder().also { avcDecoder = it }).decode(frame)
        } else SkiaImage.makeFromEncoded(frame.jpeg).use { image ->
            check(image.width == frame.width && image.height == frame.height)
            image.toComposeImageBitmap()
        }
    }.getOrNull()
}
