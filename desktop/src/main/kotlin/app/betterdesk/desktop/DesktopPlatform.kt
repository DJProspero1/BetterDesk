package app.betterdesk.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import app.betterdesk.core.*
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.IntByReference
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DesktopPlatform : RemotePlatform {
    override val isAndroid = false
    override val defaultName = System.getProperty("user.name", "Helper")
    private val active = AtomicBoolean(false)
    private var capture: java.util.concurrent.ScheduledExecutorService? = null
    private var robot: Robot? = null
    private var bounds = Rectangle()
    private var sequence = 0L
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
                var scaled: BufferedImage? = null
                executor.scheduleAtFixedRate({
                    if (!active.get()) return@scheduleAtFixedRate
                    try {
                        val raw = robot!!.createScreenCapture(bounds)
                        val scale = minOf(1.0, 1440.0 / maxOf(raw.width, raw.height))
                        val target = scaled ?: BufferedImage((raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB).also { scaled = it }
                        target.createGraphics().apply {
                            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                            drawImage(raw, 0, 0, target.width, target.height, null); dispose()
                        }
                        raw.flush()
                        val bytes = target.toComposeImageBitmap().asSkiaBitmap().use { bitmap ->
                            SkiaImage.makeFromBitmap(bitmap).use { image ->
                                image.encodeToData(EncodedImageFormat.JPEG, 65)!!.use { it.bytes }
                            }
                        }
                        if (active.get()) onFrame(Packet.Frame(++sequence, target.width, target.height, bytes))
                    } catch (_: Exception) { if (active.getAndSet(false)) onStopped() }
                }, 0, 33, TimeUnit.MILLISECONDS)
                // Dispose native codec resources on the capture thread after its last task.
                captureCleanup = { executor.execute { scaled?.flush() } }
            }
            true
        } catch (_: Exception) { stopSharing(); false }
    }

    override fun stopSharing() {
        active.set(false); runCatching { captureCleanup?.invoke() }; captureCleanup = null
        capture?.shutdown(); capture = null
        runCatching { robot?.mouseRelease(InputEvent.BUTTON1_DOWN_MASK or InputEvent.BUTTON3_DOWN_MASK) }
    }

    private var captureCleanup: (() -> Unit)? = null

    private fun isOurForeground(): Boolean {
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(User32.INSTANCE.GetForegroundWindow(), pid)
        return pid.value == Kernel32.INSTANCE.GetCurrentProcessId()
    }

    override fun applyInput(input: Message) {
        if (!active.get() || !input.isValidInput()) return
        val r = robot ?: return
        val x = bounds.x + (input.x * (bounds.width - 1)).toInt()
        val y = bounds.y + (input.y * (bounds.height - 1)).toInt()
        if (input.action in setOf("tap", "right", "swipe", "scrollUp", "scrollDown")) {
            // A helper cannot press this app's own consent/stop controls.
            if (Window.getWindows().any { it.isVisible && it.bounds.contains(x, y) }) return
        } else if (isOurForeground()) return
        when (input.action) {
            "tap", "right" -> {
                r.mouseMove(x, y)
                val button = if (input.action == "right") InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
                try { r.mousePress(button) } finally { r.mouseRelease(button) }
            }
            "swipe" -> {
                val endX = bounds.x + (input.endX * (bounds.width - 1)).toInt()
                val endY = bounds.y + (input.endY * (bounds.height - 1)).toInt()
                if (Window.getWindows().any { it.isVisible && it.bounds.intersectsLine(x.toDouble(), y.toDouble(), endX.toDouble(), endY.toDouble()) }) return
                r.mouseMove(x, y)
                try { r.mousePress(InputEvent.BUTTON1_DOWN_MASK); r.mouseMove(endX, endY) } finally { r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
            }
            "scrollUp", "scrollDown" -> { r.mouseMove(x, y); r.mouseWheel(if (input.action == "scrollUp") -3 else 3) }
            "enter" -> press(KeyEvent.VK_ENTER)
            "backspace" -> press(KeyEvent.VK_BACK_SPACE)
            "back" -> press(KeyEvent.VK_ESCAPE)
            "home" -> press(KeyEvent.VK_HOME)
            "text" -> input.text.forEach { char ->
                if (active.get() && !isOurForeground()) {
                    unicode(char, false); unicode(char, true)
                }
            }
        }
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
        SkiaImage.makeFromEncoded(frame.jpeg).use { image ->
            check(image.width == frame.width && image.height == frame.height)
            image.toComposeImageBitmap()
        }
    }.getOrNull()
}
