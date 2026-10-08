package app.betterdesk.desktop

import com.sun.jna.*
import com.sun.jna.Function
import com.sun.jna.platform.win32.Guid
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import org.jetbrains.skia.*
import java.awt.Rectangle
import java.io.Closeable

internal interface NativeCapture : Closeable {
    val width: Int
    val height: Int
    fun jpeg(): ByteArray?
}

/** Windows Desktop Duplication. All D3D calls and resource release stay on the capture thread. */
internal class DxgiCapture(bounds: Rectangle, override val width: Int, override val height: Int) : NativeCapture {
    private interface D3D : StdCallLibrary {
        fun D3D11CreateDevice(adapter: Pointer?, driver: Int, software: Pointer?, flags: Int,
                             levels: Pointer?, count: Int, sdk: Int, device: PointerByReference,
                             level: IntByReference, context: PointerByReference): Int
    }
    // Retain the JNA proxy while COM vtables point into d3d11.dll. A temporary
    // proxy can be collected and unload the DLL underneath otherwise live COM objects.
    private companion object { val api = Native.load("d3d11", D3D::class.java) }
    private val owned = mutableListOf<Pointer>()
    private lateinit var device: Pointer
    private lateinit var context: Pointer
    private lateinit var duplication: Pointer
    private var staging: Pointer? = null
    private var pixels = ByteArray(0)
    private var rawWidth = 0
    private var rawHeight = 0
    private val frameInfo = Memory(64)
    private val mapped = Memory(16)
    private val surface = Surface.makeRasterN32Premul(width, height)
    private var closed = false

    init {
        try {
            val outputDevice = PointerByReference()
            val outputContext = PointerByReference()
            checkHr(api.D3D11CreateDevice(null, 1, null, 0x20, null, 0, 7, outputDevice, IntByReference(), outputContext))
            device = outputDevice.value.also(owned::add)
            context = outputContext.value.also(owned::add)
            val dxgi = query(device, "{54EC77FA-1377-44E6-8C32-88FD5F44C84C}").also(owned::add)
            val adapter = result(dxgi, 7).also(owned::add)
            val output = result(adapter, 7, 0).also(owned::add)
            val description = Memory(96)
            checkHr(call(output, 7, description))
            check(description.getInt(64) == bounds.x && description.getInt(68) == bounds.y)
            check(description.getInt(72) - description.getInt(64) == bounds.width)
            check(description.getInt(76) - description.getInt(68) == bounds.height)
            check(description.getInt(84) in 0..1) { "Rotated display needs fallback capture" }
            val output1 = query(output, "{00CDDEA8-939B-4B83-A340-A685226666CC}").also(owned::add)
            duplication = result(output1, 22, device).also(owned::add)
        } catch (e: Exception) { close(); throw e }
    }

    override fun jpeg(): ByteArray? {
        check(!closed)
        val resource = PointerByReference()
        val hr = call(duplication, 8, 40, frameInfo, resource)
        if (hr == 0x887A0027.toInt()) return null // no new frame: never inflate FPS with repeats
        checkHr(hr)
        var texture: Pointer? = null
        try {
            if (frameInfo.getLong(0) == 0L) return null // pointer-only update
            texture = query(resource.value, "{6F15AAF2-D208-4E89-9AB4-489535D34F9C}")
            if (staging == null) {
                val desc = Memory(44)
                voidCall(texture, 10, desc)
                rawWidth = desc.getInt(0); rawHeight = desc.getInt(4)
                check(rawWidth > 0 && rawHeight > 0 && rawWidth.toLong() * rawHeight <= 16_777_216)
                check(desc.getInt(16) == 87) { "Unsupported desktop format" }
                desc.setInt(8, 1); desc.setInt(12, 1); desc.setInt(20, 1); desc.setInt(24, 0)
                desc.setInt(28, 3); desc.setInt(32, 0); desc.setInt(36, 0x20000); desc.setInt(40, 0)
                staging = result(device, 5, desc, null).also(owned::add)
            }
            voidCall(context, 47, staging, texture)
            checkHr(call(context, 14, staging, 0, 1, 0, mapped))
            val rowPitch: Int
            try {
                rowPitch = mapped.getInt(8)
                check(rowPitch >= rawWidth * 4 && rowPitch.toLong() * rawHeight <= 80_000_000)
                if (pixels.size != rowPitch * rawHeight) pixels = ByteArray(rowPitch * rawHeight)
                mapped.getPointer(0).read(0, pixels, 0, pixels.size)
            } finally { voidCall(context, 15, staging, 0) }
            val info = ImageInfo(rawWidth, rawHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            Image.makeRaster(info, pixels, rowPitch).use { image ->
                surface.canvas.drawImageRect(image, Rect.makeWH(rawWidth.toFloat(), rawHeight.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()), SamplingMode.LINEAR, null, true)
            }
            return surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.JPEG, 65)!!.use { it.bytes } }
        } finally {
            texture?.let { call(it, 2) }
            resource.value?.let { call(it, 2) }
            call(duplication, 14)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        owned.asReversed().forEach { call(it, 2) }
        owned.clear(); surface.close(); frameInfo.close(); mapped.close()
    }

    private fun query(instance: Pointer, iid: String): Pointer = result(instance, 0, Guid.GUID(iid))
    private fun result(instance: Pointer, index: Int, vararg args: Any?): Pointer {
        val reference = PointerByReference()
        checkHr(call(instance, index, *args, reference))
        return reference.value ?: error("Missing native result")
    }
    private fun function(instance: Pointer, index: Int) = Function.getFunction(instance.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
    private fun call(instance: Pointer, index: Int, vararg args: Any?): Int = function(instance, index).invokeInt(arrayOf(instance, *args))
    private fun voidCall(instance: Pointer, index: Int, vararg args: Any?) = function(instance, index).invokeVoid(arrayOf(instance, *args))
    private fun checkHr(hr: Int) { check(hr >= 0) { "Desktop Duplication failure ${hr.toUInt().toString(16)}" } }
}
