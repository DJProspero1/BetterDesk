package app.betterdesk

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import app.betterdesk.core.Packet
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object CaptureBridge {
    @Volatile var active = false
    @Volatile var fullDisplay = true
    @Volatile var onFrame: ((Packet.Frame) -> Unit)? = null
    var onReady: ((Boolean) -> Unit)? = null
    var onStopped: (() -> Unit)? = null
}

class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val worker = HandlerThread("BetterDesk capture")
    private var handler: Handler? = null
    private var sequence = 0L
    private var lastFrame = 0L
    private var width = 0
    private var height = 0

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { Runtime.session?.stop(); stopSelf(); return START_NOT_STICKY }
        if (projection != null || intent == null) return START_NOT_STICKY
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("screen", "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenShareService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "screen")
            .setSmallIcon(app.betterdesk.R.drawable.ic_betterdesk).setContentTitle("BetterDesk")
            .setContentText(if (resources.configuration.locales[0].language == "pt") "O ecrã está a ser partilhado" else "Your screen is being shared")
            .setContentIntent(open).setOngoing(true)
            .addAction(0, if (resources.configuration.locales[0].language == "pt") "Terminar ajuda" else "Stop help", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, notification)
        try {
            @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("projection") ?: error("Missing consent")
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(intent.getIntExtra("resultCode", Activity.RESULT_CANCELED), data)
            worker.start(); handler = Handler(worker.looper)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width > 0 && height > 0) resize(width, height)
                }
            }, Handler(Looper.getMainLooper()))
            val dimensions = displayDimensions()
            resize(dimensions.first, dimensions.second)
            CaptureBridge.active = true
            CaptureBridge.onReady?.invoke(true)
        } catch (_: Exception) { CaptureBridge.onReady?.invoke(false); stopSelf() }
        return START_NOT_STICKY
    }

    private fun resize(originalWidth: Int, originalHeight: Int) {
        val screen = displayDimensions()
        CaptureBridge.fullDisplay = originalWidth == screen.first && originalHeight == screen.second
        Runtime.session?.refreshPermissions()
        val scale = minOf(1.0, 1280.0 / maxOf(originalWidth, originalHeight))
        val w = (originalWidth * scale).roundToInt().coerceAtLeast(1)
        val h = (originalHeight * scale).roundToInt().coerceAtLeast(1)
        if (w == width && h == height) return
        width = w; height = h
        val nextReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        nextReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (!CaptureBridge.active || now - lastFrame < 180) return@setOnImageAvailableListener
                lastFrame = now
                val plane = image.planes[0]
                val paddedWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                val bytes = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 65, bytes)
                CaptureBridge.onFrame?.invoke(Packet.Frame(++sequence, bitmap.width, bitmap.height, bytes.toByteArray()))
                if (bitmap !== padded) bitmap.recycle()
                padded.recycle()
            } catch (_: Exception) { Handler(Looper.getMainLooper()).post { stopSelf() } }
            finally { image.close() }
        }, handler)
        if (display == null) {
            display = projection!!.createVirtualDisplay("BetterDesk", w, h, resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, nextReader.surface, null, handler)
        } else {
            display!!.resize(w, h, resources.displayMetrics.densityDpi)
            display!!.surface = nextReader.surface
        }
        val old = reader; reader = nextReader; old?.close()
    }

    private fun displayDimensions(): Pair<Int, Int> {
        val manager = getSystemService(android.view.WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") manager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    override fun onDestroy() {
        val wasActive = CaptureBridge.active
        CaptureBridge.active = false
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        worker.quitSafely()
        if (wasActive) CaptureBridge.onStopped?.invoke()
        super.onDestroy()
    }
}
