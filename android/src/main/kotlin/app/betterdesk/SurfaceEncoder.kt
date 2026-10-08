package app.betterdesk

import android.media.*
import android.os.Build
import android.os.Handler
import android.view.Surface
import app.betterdesk.core.Packet
import java.io.Closeable

/** Direct projection encoder. Dropped-frame recovery explicitly requests a new independent frame. */
internal class SurfaceEncoder(requestedWidth: Int, requestedHeight: Int, handler: Handler,
                              onFrame: (Packet.Frame) -> Unit, onError: () -> Unit) : Closeable {
    var width = requestedWidth
        private set
    var height = requestedHeight
        private set
    private var codec: MediaCodec? = null
    lateinit var surface: Surface
        private set
    private var headers = ByteArray(0)
    private var closed = false

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setFloat(MediaFormat.KEY_OPERATING_RATE, 60f)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) ?: error("AVC encoder unavailable")
            val encoder = MediaCodec.createByCodecName(name).also { codec = it }
            val software = if (Build.VERSION.SDK_INT >= 29) !encoder.codecInfo.isHardwareAccelerated
                else name.startsWith("OMX.google.") || name.startsWith("c2.android.")
            if (software) {
                // Preserve motion on CPU-only devices without adding a quality setting.
                val scale = minOf(1.0, 960.0 / maxOf(width, height))
                width = ((width * scale).toInt() / 2 * 2).coerceAtLeast(2)
                height = ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
                format.setInteger(MediaFormat.KEY_WIDTH, width)
                format.setInteger(MediaFormat.KEY_HEIGHT, height)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            }
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) { if (!closed) onError() }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    headers = (0..1).mapNotNull { index -> format.getByteBuffer("csd-$index")?.duplicate()?.let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) } }
                        .fold(ByteArray(0)) { a, b -> a + b }
                }
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (closed) return
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = (if (key) headers else ByteArray(0)) + ByteArray(info.size).also(buffer::get)
                            onFrame(Packet.Frame(1, width, height, bytes, "avc", key))
                        }
                    } catch (_: Exception) { onError() }
                    finally { codec.releaseOutputBuffer(index, false) }
                }
            }, handler)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            encoder.start()
        } catch (error: Exception) { close(); throw error }
    }

    fun requestKeyFrame() {
        if (!closed) runCatching { codec?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec?.stop() }; codec?.release(); codec = null
        if (::surface.isInitialized) surface.release()
    }
}
