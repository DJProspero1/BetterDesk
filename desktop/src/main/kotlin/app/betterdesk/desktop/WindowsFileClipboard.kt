package app.betterdesk.desktop

import app.betterdesk.core.FileOffer
import com.sun.jna.*
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** Explorer virtual files: metadata on copy; encrypted download only when pasted. Windows x64. */
internal class WindowsFileClipboard(private val sequenceChanged: () -> Unit) {
    private interface Ole : StdCallLibrary {
        fun OleInitialize(reserved: Pointer?): Int
        fun OleSetClipboard(data: Pointer?): Int
        fun OleIsCurrentClipboard(data: Pointer): Int
        fun ReleaseStgMedium(medium: Pointer)
    }
    private interface Shell : StdCallLibrary {
        fun SHCreateStdEnumFmtEtc(count: Int, formats: Pointer, output: PointerByReference): Int
        fun SHCreateStreamOnFileEx(path: WString, mode: Int, attributes: Int, create: Int, template: Pointer?, output: PointerByReference): Int
    }
    private interface Kernel : StdCallLibrary {
        fun GlobalAlloc(flags: Int, size: Long): Pointer?
        fun GlobalLock(handle: Pointer): Pointer?
        fun GlobalUnlock(handle: Pointer): Int
        fun GlobalFree(handle: Pointer): Pointer?
    }
    private interface User : StdCallLibrary { fun RegisterClipboardFormatW(name: WString): Int }
    private val ole = Native.load("ole32", Ole::class.java)
    private val shell = Native.load("shlwapi", Shell::class.java)
    // SHCreateStdEnumFmtEtc is exported by shell32, not shlwapi.
    private val enumShell = NativeLibrary.getInstance("shell32").getFunction("SHCreateStdEnumFmtEtc")
    private val kernel = Native.load("kernel32", Kernel::class.java)
    private val user = Native.load("user32", User::class.java)
    private val descriptor = user.RegisterClipboardFormatW(WString("FileGroupDescriptorW"))
    private val contents = user.RegisterClipboardFormatW(WString("FileContents"))
    private val effect = user.RegisterClipboardFormatW(WString("Preferred DropEffect"))
    private val queue = LinkedBlockingQueue<() -> Unit>()
    private val pinned = ConcurrentHashMap.newKeySet<DataObject>()
    private var current: DataObject? = null
    init {
        require(Native.POINTER_SIZE == 8)
        Thread({
            if (ole.OleInitialize(null) < 0) return@Thread
            val message = WinUser.MSG()
            while (true) {
                queue.poll()?.let { runCatching(it) }
                while (User32.INSTANCE.PeekMessage(message, null, 0, 0, 1)) {
                    User32.INSTANCE.TranslateMessage(message); User32.INSTANCE.DispatchMessage(message)
                }
                Thread.sleep(10)
            }
        }, "BetterDesk file clipboard").apply { isDaemon = true; start() }
    }
    fun offer(offer: FileOffer, fetch: () -> CompletableFuture<File>): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        queue.offer {
            val data = DataObject(offer, fetch)
            pinned.add(data)
            if (ole.OleSetClipboard(data.pointer) >= 0) { current = data; sequenceChanged(); result.complete(Unit) }
            else { data.valid = false; result.completeExceptionally(java.io.IOException("Clipboard unavailable")) }
            data.release()
        }
        return result
    }
    fun clear() {
        // Invalidation is immediate even if Explorer is waiting inside GetData.
        pinned.forEach { it.valid = false }
        queue.offer {
            current?.let { if (ole.OleIsCurrentClipboard(it.pointer) == 0) { ole.OleSetClipboard(null); sequenceChanged() } }
            current = null
        }
    }

    private class Method(private val parameters: Array<Class<*>>, private val run: (Array<Any?>) -> Int) : CallbackProxy, StdCallLibrary.StdCallCallback {
        override fun getParameterTypes(): Array<Class<*>> = parameters
        override fun getReturnType(): Class<*> = Int::class.javaPrimitiveType!!
        override fun callback(args: Array<Any?>): Any = try { run(args) } catch (_: Throwable) { 0x80004005.toInt() }
    }
    private inner class DataObject(val offer: FileOffer, val fetch: () -> CompletableFuture<File>) {
        @Volatile var valid = true
        private val refs = AtomicInteger(1)
        private val callbacks = ArrayList<Method>()
        private val allocations = ArrayList<Memory>()
        private var asyncMode = true
        private var inOperation = false
        lateinit var pointer: Pointer
        private lateinit var asyncPointer: Pointer
        init {
            val p = Pointer::class.java; val i = Int::class.javaPrimitiveType!!
            fun method(vararg types: Class<*>, body: (Array<Any?>) -> Int): Method = Method(arrayOf(*types), body).also(callbacks::add)
            val query = method(p, p, p) { a ->
                val iid = Guid.GUID(a[1] as Pointer).toGuidString().uppercase()
                val result = when (iid) {
                    "{00000000-0000-0000-C000-000000000046}", "{0000010E-0000-0000-C000-000000000046}" -> pointer
                    "{3D8B0590-F691-11D2-8EA9-006097DF5BD4}" -> asyncPointer
                    else -> null
                }
                (a[2] as Pointer).setPointer(0, result)
                if (result == null) 0x80004002.toInt() else { refs.incrementAndGet(); 0 }
            }
            val addRef = method(p) { refs.incrementAndGet() }
            val release = method(p) { release() }
            val getData = method(p, p, p) { a -> getData(a[1] as Pointer, a[2] as Pointer) }
            val unsupportedData = method(p, p, p) { 0x80004001.toInt() }
            val queryData = method(p, p) { a -> if (supported(a[1] as Pointer)) 0 else 0x80040064.toInt() }
            val canonical = method(p, p, p) { a -> (a[2] as Pointer).setPointer(8, null); 0x80004001.toInt() }
            val setData = method(p, p, p, i) { a -> if (a[3] as Int != 0) ole.ReleaseStgMedium(a[2] as Pointer); 0 }
            val enumerate = method(p, i, p) { a ->
                if (a[1] as Int != 1) 0x80004001.toInt() else {
                    val formats = Memory(96).apply { clear() }
                    format(formats, 0, descriptor, 1); format(formats, 32, contents, 4); format(formats, 64, effect, 1)
                    val result = PointerByReference()
                    val status = enumShell.invokeInt(arrayOf(3, formats, result))
                    (a[2] as Pointer).setPointer(0, result.value); status
                }
            }
            val advise = method(p, p, i, p, p) { 0x80040003.toInt() }
            val unadvise = method(p, i) { 0x80040003.toInt() }
            val enumAdvise = method(p, p) { 0x80040003.toInt() }
            pointer = table(listOf(query, addRef, release, getData, unsupportedData, queryData, canonical, setData, enumerate, advise, unadvise, enumAdvise))
            asyncPointer = table(listOf(query, addRef, release,
                method(p, i) { a -> asyncMode = a[1] as Int != 0; 0 },
                method(p, p) { a -> (a[1] as Pointer).setInt(0, if (asyncMode) 1 else 0); 0 },
                method(p, p) { inOperation = true; 0 },
                method(p, p) { a -> (a[1] as Pointer).setInt(0, if (inOperation) 1 else 0); 0 },
                method(p, i, p, i) { inOperation = false; 0 }))
        }
        private fun table(methods: List<Method>): Pointer {
            val vtable = Memory(methods.size * 8L)
            methods.forEachIndexed { index, callback -> vtable.setPointer(index * 8L, CallbackReference.getFunctionPointer(callback)) }
            val instance = Memory(8).apply { setPointer(0, vtable) }
            allocations.add(vtable); allocations.add(instance)
            return instance
        }
        fun release(): Int = refs.decrementAndGet().also { if (it == 0) pinned.remove(this) }
        private fun format(memory: Pointer, offset: Long, id: Int, medium: Int) {
            memory.setShort(offset, id.toShort()); memory.setInt(offset + 16, 1)
            memory.setInt(offset + 20, -1); memory.setInt(offset + 24, medium)
        }
        private fun supported(format: Pointer): Boolean {
            if (!valid || format.getInt(16) != 1) return false
            val id = format.getShort(0).toInt() and 0xffff
            val medium = format.getInt(24)
            return (id in setOf(descriptor, effect) && medium and 1 != 0) ||
                (id == contents && medium and 4 != 0 && format.getInt(20) in -1 until offer.entries.size)
        }
        private fun global(bytes: ByteArray): Pointer {
            val handle = kernel.GlobalAlloc(0x42, bytes.size.toLong()) ?: error("Allocation failed")
            val memory = kernel.GlobalLock(handle) ?: run { kernel.GlobalFree(handle); error("Allocation failed") }
            memory.write(0, bytes, 0, bytes.size); kernel.GlobalUnlock(handle)
            return handle
        }
        private fun getData(format: Pointer, medium: Pointer): Int {
            medium.clear(24)
            if (!supported(format)) return 0x80040064.toInt()
            when (format.getShort(0).toInt() and 0xffff) {
                descriptor -> {
                    val bytes = Memory(4 + offer.entries.size * 592L).apply { clear(); setInt(0, offer.entries.size) }
                    offer.entries.forEachIndexed { index, entry ->
                        val offset = 4 + index * 592L
                        bytes.setInt(offset, 0x4044); bytes.setInt(offset + 36, if (entry.directory) 0x10 else 0x80)
                        bytes.setInt(offset + 64, (entry.size ushr 32).toInt()); bytes.setInt(offset + 68, entry.size.toInt())
                        bytes.setWideString(offset + 72, entry.path.replace('/', '\\'))
                    }
                    medium.setInt(0, 1); medium.setPointer(8, global(bytes.getByteArray(0, bytes.size().toInt())))
                }
                effect -> { medium.setInt(0, 1); medium.setPointer(8, global(byteArrayOf(1, 0, 0, 0))) }
                contents -> {
                    val index = format.getInt(20).let { if (it == -1 && offer.entries.size == 1) 0 else it }
                    if (index !in offer.entries.indices || offer.entries[index].directory) return 0x80040068.toInt()
                    val root = fetch().get(30, java.util.concurrent.TimeUnit.MINUTES)
                    check(valid)
                    val file = File(root, offer.entries[index].path)
                    require(file.canonicalPath.startsWith(root.canonicalPath + File.separator) && file.isFile)
                    val stream = PointerByReference()
                    val result = shell.SHCreateStreamOnFileEx(WString(file.absolutePath), 0x40, 0x80, 0, null, stream)
                    if (result < 0) return result
                    medium.setInt(0, 4); medium.setPointer(8, stream.value)
                }
            }
            return 0
        }
    }
}
