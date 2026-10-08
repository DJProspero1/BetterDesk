package app.betterdesk.motionfixture

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.WindowManager

/** Separate opt-in test APK; never bundled into BetterDesk. No network or recording. */
class MotionActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        val mode = windowManager.defaultDisplay.supportedModes.maxByOrNull { it.refreshRate }
        mode?.let { window.attributes = window.attributes.apply { preferredDisplayModeId = it.modeId; preferredRefreshRate = it.refreshRate } }
        setContentView(object : View(this) {
            val paint = Paint()
            var frame = 0
            override fun onDraw(canvas: Canvas) {
                frame++
                canvas.drawColor(Color.rgb(234, 242, 237))
                paint.color = Color.rgb(34, 99, 78)
                val time = System.nanoTime() / 1_000_000
                repeat(14) { index ->
                    val x = ((index * width / 14 + time / 3) % width).toFloat()
                    canvas.drawRect(x, height * .25f, x + width / 30f, height * .85f, paint)
                }
                paint.textSize = width / 24f
                canvas.drawText("BetterDesk • motion test", width * .05f, height * .1f, paint)
                canvas.drawText("Frame $frame", width * .05f, height * .16f, paint)
                postInvalidateOnAnimation()
            }
        })
        if (intent.getBooleanExtra("codec", false)) {
            val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), 1)
        }
    }

    @Deprecated("Test activity uses the platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            startForegroundService(android.content.Intent(this, CodecBenchmarkService::class.java)
                .putExtra("result", resultCode).putExtra("consent", data))
        }
    }
}
