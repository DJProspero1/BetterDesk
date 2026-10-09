package app.betterdesk.desktop

import app.betterdesk.core.Packet
import app.betterdesk.core.Wire
import com.sun.jna.*
import com.sun.jna.Function
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*

/** Captures the default render endpoint with WASAPI loopback. Never opens an input endpoint. */
internal class WasapiAudio {
    @Volatile var failure = ""; private set
    private var current: AtomicBoolean? = null
    private var worker: Thread? = null
    @Synchronized fun start(onAudio: (Packet.Audio) -> Unit): Boolean {
        if (current?.get() == true) return false
        // Each worker owns its stop flag. An old worker's finally must never stop
        // a fresh capture after a rapid switch or reconnect.
        val active = AtomicBoolean(true).also { current = it }
        val ready = CompletableFuture<Boolean>()
        worker = Thread({
            val owned = mutableListOf<Pointer>()
            var client: Pointer? = null
            var format: Pointer? = null
            var initialized = false
            try {
                checkHr(Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED).toInt()); initialized = true
                val enumeratorRef = PointerByReference()
                checkHr(Ole32.INSTANCE.CoCreateInstance(Guid.GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}"), null, 23,
                    Guid.GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}"), enumeratorRef).toInt())
                val enumerator = enumeratorRef.value.also(owned::add)
                val endpoint = result(enumerator, 4, 0, 1).also(owned::add) // eRender, eMultimedia
                val audioClient = result(endpoint, 3, Guid.GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}"), 23, null).also(owned::add)
                client = audioClient
                format = result(audioClient, 8)
                val channels = format.getShort(2).toInt() and 0xffff
                val rate = format.getInt(4)
                val block = format.getShort(12).toInt() and 0xffff
                val bits = format.getShort(14).toInt() and 0xffff
                val tag = format.getShort(0).toInt() and 0xffff
                val encoding = if (tag == 0xfffe && (format.getShort(16).toInt() and 0xffff) >= 22) format.getInt(24) else tag
                require(channels in 1..8 && rate in 8_000..192_000 && block == channels * bits / 8)
                require((encoding == 3 && bits == 32) || (encoding == 1 && bits in setOf(16, 24, 32)))
                checkHr(call(audioClient, 3, 0, 0x00020000, 1_000_000L, 0L, format, null)) // shared, LOOPBACK
                val capture = result(audioClient, 14, Guid.GUID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}")).also(owned::add)
                val converter = StereoPcm(rate, channels, bits, encoding == 3, onAudio)
                check(active.get())
                checkHr(call(audioClient, 10))
                ready.complete(true)
                val count = IntByReference()
                while (active.get()) {
                    checkHr(call(capture, 5, count))
                    if (count.value == 0) { Thread.sleep(5); continue }
                    val data = PointerByReference(); val frames = IntByReference(); val flags = IntByReference()
                    checkHr(call(capture, 3, data, frames, flags, null, null))
                    try {
                        require(frames.value in 0..192_000)
                        if (active.get()) converter.accept(if (flags.value and 2 != 0) ByteArray(frames.value * block)
                            else data.value.getByteArray(0, frames.value * block), frames.value)
                    } finally { checkHr(call(capture, 4, frames.value)) }
                }
            } catch (error: Exception) { failure = error.javaClass.simpleName + ": " + error.message; ready.complete(false) }
            finally {
                active.set(false); ready.complete(false)
                client?.let { runCatching { call(it, 11) } }
                format?.let { Ole32.INSTANCE.CoTaskMemFree(it) }
                owned.asReversed().forEach { runCatching { call(it, 2) } }
                if (initialized) Ole32.INSTANCE.CoUninitialize()
            }
        }, "BetterDesk playback capture").apply { isDaemon = true; start() }
        return runCatching { ready.get(3, TimeUnit.SECONDS) && active.get() }.getOrElse { stop(); false }
    }
    @Synchronized fun stop() { current?.set(false); worker?.interrupt() }
    private fun call(instance: Pointer, index: Int, vararg args: Any?): Int = Function.getFunction(
        instance.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION).invokeInt(arrayOf(instance, *args))
    private fun result(instance: Pointer, index: Int, vararg args: Any?): Pointer {
        val out = PointerByReference(); checkHr(call(instance, index, *args, out)); return out.value ?: error("Missing audio interface")
    }
    private fun checkHr(value: Int) { check(value >= 0) { "Native playback capture unavailable: ${value.toUInt().toString(16)}" } }
}

/** Linear rate conversion with state carried across WASAPI buffers; fixed, bounded output packets. */
internal class StereoPcm(private val rate: Int, private val channels: Int, private val bits: Int, private val floating: Boolean,
    private val emit: (Packet.Audio) -> Unit) {
    private val packet = ByteArray(Wire.AUDIO_BYTES)
    private var used = 0
    private var sequence = 0L
    private var inputIndex = 0L
    private var nextOutput = 0.0
    private var previousLeft = 0.0
    private var previousRight = 0.0
    fun accept(bytes: ByteArray, frames: Int) {
        val width = bits / 8
        require(bytes.size == frames * channels * width)
        fun sample(index: Int): Double {
            val offset = index * width
            var value = 0
            repeat(width) { value = value or ((bytes[offset + it].toInt() and 255) shl (it * 8)) }
            return if (floating) Float.fromBits(value).toDouble().let { if (it.isFinite()) it.coerceIn(-1.0, 1.0) else 0.0 }
            else when (bits) { 16 -> value.toShort() / 32768.0; 24 -> (value shl 8 shr 8) / 8388608.0; else -> value / 2147483648.0 }
        }
        repeat(frames) { frame ->
            val left = sample(frame * channels)
            val right = if (channels == 1) left else sample(frame * channels + 1)
            // The first two channels retain the render endpoint's front stereo pair.
            if (inputIndex == 0L) { previousLeft = left; previousRight = right }
            while (nextOutput <= inputIndex) {
                val fraction = (nextOutput - (inputIndex - 1)).coerceIn(0.0, 1.0)
                put(previousLeft + (left - previousLeft) * fraction)
                put(previousRight + (right - previousRight) * fraction)
                if (used == packet.size) { emit(Packet.Audio(++sequence, packet.copyOf())); used = 0 }
                nextOutput += rate.toDouble() / Wire.AUDIO_RATE
            }
            previousLeft = left; previousRight = right; inputIndex++
        }
    }
    private fun put(value: Double) {
        val sample = (value.coerceIn(-1.0, 1.0) * 32767).toInt()
        packet[used++] = sample.toByte(); packet[used++] = (sample shr 8).toByte()
    }
}

internal class DesktopAudioPlayer {
    @Volatile private var line: SourceDataLine? = null
    private val lock = Any()
    private var enabled = false
    fun start() = synchronized(lock) { enabled = true }
    fun play(audio: Packet.Audio) {
        val output = synchronized(lock) {
            if (!enabled) return
            line ?: AudioSystem.getSourceDataLine(AudioFormat(48_000f, 16, 2, true, false)).apply {
                open(AudioFormat(48_000f, 16, 2, true, false), Wire.AUDIO_BYTES * 4); start(); line = this
            }
        }
        output.write(audio.pcm, 0, audio.pcm.size)
    }
    fun stop() = synchronized(lock) {
        enabled = false
        val old = line; line = null
        runCatching { old?.stop(); old?.flush(); old?.close() }; Unit
    }
}
