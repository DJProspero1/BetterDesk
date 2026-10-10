package app.betterdesk.rtc

import java.nio.ByteBuffer

/** Small, copied I420 frames for the opt-in bridge proof, never an OS capture owner. */
class RtcVideoFrame(val width: Int, val height: Int, y: ByteArray, u: ByteArray, v: ByteArray,
    val timestampNs: Long = System.nanoTime()) {
    private val luma: ByteArray
    private val chromaU: ByteArray
    private val chromaV: ByteArray
    init {
        require(width in 4..640 && height in 4..360 && width % 2 == 0 && height % 2 == 0)
        require(y.size == width * height && u.size == y.size / 4 && v.size == u.size)
        require(timestampNs > 0)
        luma = y.copyOf(); chromaU = u.copyOf(); chromaV = v.copyOf()
    }
    fun copyPlanes(targetY: ByteBuffer, strideY: Int, targetU: ByteBuffer, strideU: Int, targetV: ByteBuffer, strideV: Int) {
        fun copy(source: ByteArray, target: ByteBuffer, stride: Int, columns: Int, rows: Int) {
            require(stride >= columns && target.remaining() >= stride * (rows - 1) + columns)
            val buffer = target.slice()
            for (row in 0 until rows) { buffer.position(row * stride); buffer.put(source, row * columns, columns) }
        }
        copy(luma, targetY, strideY, width, height)
        copy(chromaU, targetU, strideU, width / 2, height / 2)
        copy(chromaV, targetV, strideV, width / 2, height / 2)
    }
    override fun toString() = "RtcVideoFrame(redacted)"
}

/** Synthetic fixture samples only; never serialize decoded pixels or native stats. */
class RtcVideoSample(val width: Int, val height: Int, val rotation: Int, val quarters: List<Int>) {
    override fun toString() = "RtcVideoSample(redacted)"
    companion object {
        fun read(width: Int, height: Int, rotation: Int, y: ByteBuffer, strideY: Int): RtcVideoSample {
            require(width in 4..640 && height in 4..360 && rotation in listOf(0, 90, 180, 270))
            require(strideY >= width && y.remaining() >= strideY * (height - 1) + width)
            val buffer = y.slice()
            val values = listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).map { (x, row) ->
                buffer.get((height * row / 4) * strideY + width * x / 4).toInt() and 255
            }
            return RtcVideoSample(width, height, rotation, values)
        }
    }
}

data class RtcVideoCounters(val encoded: Long, val decoded: Long, val sentBytes: Long, val receivedBytes: Long)
