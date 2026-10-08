package app.betterdesk.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import org.jetbrains.skia.*
import java.awt.Rectangle

/** Reuses a native DIB. Resize before readback, avoiding Robot's full-resolution Java pixel copies. */
internal class GdiCapture(private val bounds: Rectangle, override val width: Int, override val height: Int) : NativeCapture {
    private interface Blit : StdCallLibrary {
        fun StretchBlt(dst: WinDef.HDC, x: Int, y: Int, w: Int, h: Int, src: WinDef.HDC,
                       sx: Int, sy: Int, sw: Int, sh: Int, operation: Int): Boolean
        fun SetStretchBltMode(dc: WinDef.HDC, mode: Int): Int
        fun GdiFlush(): Boolean
    }
    private val gdi = GDI32.INSTANCE
    private val blit = Native.load("gdi32", Blit::class.java)
    private val screen = User32.INSTANCE.GetDC(null) ?: error("Display unavailable")
    private var target: WinDef.HDC? = null
    private val pointer = PointerByReference()
    private var bitmap: WinDef.HBITMAP? = null
    private var previous: WinNT.HANDLE? = null
    private var closed = false
    private val pixels = ByteArray(width * height * 4)
    private val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)

    init {
      try {
        target = gdi.CreateCompatibleDC(screen) ?: error("Capture context unavailable")
        val dib = WinGDI.BITMAPINFO()
        dib.bmiHeader.biWidth = width
        dib.bmiHeader.biHeight = -height
        dib.bmiHeader.biPlanes = 1
        dib.bmiHeader.biBitCount = 32
        dib.bmiHeader.biCompression = WinGDI.BI_RGB
        bitmap = gdi.CreateDIBSection(target, dib, WinGDI.DIB_RGB_COLORS, pointer, null, 0) ?: error("Capture buffer unavailable")
        previous = gdi.SelectObject(target, bitmap)
        check(previous != null) { "Capture bitmap selection failed" }
        blit.SetStretchBltMode(target!!, 4) // HALFTONE preserves readable downscaled text.
      } catch (error: Exception) { close(); throw error }
    }

    override fun jpeg(): ByteArray {
        check(!closed)
        check(blit.StretchBlt(target!!, 0, 0, width, height, screen, bounds.x, bounds.y, bounds.width, bounds.height, GDI32.SRCCOPY))
        check(blit.GdiFlush())
        pointer.value.read(0, pixels, 0, pixels.size)
        return Bitmap().use { buffer ->
            check(buffer.installPixels(info, pixels, width * 4))
            Image.makeFromBitmap(buffer).use { image -> image.encodeToData(EncodedImageFormat.JPEG, 65)!!.use { it.bytes } }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        previous?.let { gdi.SelectObject(target, it) }
        bitmap?.let { gdi.DeleteObject(it) }
        target?.let { gdi.DeleteDC(it) }
        User32.INSTANCE.ReleaseDC(null, screen)
    }
}
