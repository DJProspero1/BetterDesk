package app.betterdesk.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.betterdesk.core.Packet
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.jetbrains.skia.*
import java.io.Closeable

/** LGPL FFmpeg decoder. All access is serialized by DesktopPlatform, including teardown. */
internal class AvcDecoder : Closeable {
    private val context = avcodec_alloc_context3(avcodec_find_decoder(AV_CODEC_ID_H264))
    private val packet = av_packet_alloc()
    private val frame = av_frame_alloc()
    private val rgb = av_frame_alloc()
    private var conversion: SwsContext? = null
    private var pixels = ByteArray(0)
    private var closed = false

    init {
        try {
            context.max_pixels(4_194_304).thread_count(1)
            check(avcodec_open2(context, avcodec_find_decoder(AV_CODEC_ID_H264), null as org.bytedeco.ffmpeg.avutil.AVDictionary?) >= 0)
        } catch (error: Exception) { close(); throw error }
    }

    fun decode(input: Packet.Frame): ImageBitmap {
        check(!closed)
        check(av_new_packet(packet, input.jpeg.size) >= 0)
        try {
            packet.data().put(input.jpeg, 0, input.jpeg.size)
            check(avcodec_send_packet(context, packet) >= 0)
            check(avcodec_receive_frame(context, frame) >= 0)
            check(frame.width() == input.width && frame.height() == input.height)
            if (rgb.width() != input.width || rgb.height() != input.height) {
                av_frame_unref(rgb)
                rgb.width(input.width).height(input.height).format(AV_PIX_FMT_BGRA)
                check(av_frame_get_buffer(rgb, 32) >= 0)
            }
            conversion = sws_getCachedContext(conversion, input.width, input.height, frame.format(),
                input.width, input.height, AV_PIX_FMT_BGRA, SWS_FAST_BILINEAR, null, null, null as DoubleArray?)
            check(conversion != null)
            check(sws_scale(conversion, frame.data(), frame.linesize(), 0, input.height, rgb.data(), rgb.linesize()) == input.height)
            val stride = rgb.linesize(0)
            if (pixels.size != stride * input.height) pixels = ByteArray(stride * input.height)
            rgb.data(0).get(pixels)
            return Image.makeRaster(ImageInfo(input.width, input.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), pixels, stride)
                .use { it.toComposeImageBitmap() }
        } finally { av_packet_unref(packet); av_frame_unref(frame) }
    }

    override fun close() {
        if (closed) return
        closed = true
        conversion?.let { sws_freeContext(it) }; conversion = null
        av_frame_free(rgb); av_frame_free(frame); av_packet_free(packet); avcodec_free_context(context)
    }
}
