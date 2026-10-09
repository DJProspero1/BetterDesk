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
    @Volatile var responsiveAt = 0L
    @Volatile var useAvc = false
    @Volatile var requestKeyFrame: (() -> Unit)? = null
    @Volatile var onFrame: ((Packet.Frame) -> Unit)? = null
    var onReady: ((Boolean) -> Unit)? = null
    var onStopped: (() -> Unit)? = null
    @Volatile var startAudio: (((Packet.Audio) -> Unit) -> Boolean)? = null
    @Volatile var stopAudio: (() -> Unit)? = null
}

class ScreenShareService : Service() {
    private var playbackCapture: PlaybackAudioCapture? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var encoder: SurfaceEncoder? = null
    private val worker = HandlerThread("BetterDesk capture")
    private var handler: Handler? = null
    private var sequence = 0L
    private var lastFrame = 0L
    private var width = 0
    private var height = 0
    private var reusablePadded: Bitmap? = null
    private var reusableFrame: Bitmap? = null
    private val encoded = ByteArrayOutputStream(256 * 1024)
    private var sampledFrames = 0L
    private var copyNanos = 0L
    private var encodeNanos = 0L
    private var sourceSampleAt = 0L
    private var sourceFrames = 0L

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { Runtime.session?.stop(); stopSelf(); return START_NOT_STICKY }
        if (projection != null || intent == null) return START_NOT_STICKY
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("screen", "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenShareService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "screen")
            .setSmallIcon(app.betterdesk.R.drawable.ic_connection).setContentTitle("BetterDesk")
            .setContentText(if (resources.configuration.locales[0].language == "pt") "O ecrã está a ser partilhado" else "Your screen is being shared")
            .setContentIntent(open).setOngoing(true)
            .addAction(0, if (resources.configuration.locales[0].language == "pt") "Desligar" else "Disconnect", stop).build()
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
            val sound = PlaybackAudioCapture(this, projection!!).also { playbackCapture = it }
            CaptureBridge.startAudio = { callback -> CaptureBridge.active && sound.start(callback) }
            CaptureBridge.stopAudio = sound::stop
            // MediaProjection now owns foreground lifetime. Avoid a second,
            // obsolete "Connection is available" notification while sharing.
            stopService(Intent(this, ConnectionService::class.java))
            handler!!.post(object : Runnable {
                override fun run() {
                    if (!CaptureBridge.active) return
                    CaptureBridge.responsiveAt = System.nanoTime()
                    handler?.postDelayed(this, 500)
                }
            })
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
        if (CaptureBridge.useAvc) {
            val next = runCatching {
                SurfaceEncoder(w - w % 2, h - h % 2, handler!!,
                    onFrame = { frame ->
                        if (CaptureBridge.active) {
                            val now = System.nanoTime()
                            if (sourceSampleAt == 0L) sourceSampleAt = now
                            sourceFrames++
                            if (now - sourceSampleAt >= 10_000_000_000) {
                                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                                    android.util.Log.d("BetterDeskPerf", "AVC source fps=${sourceFrames * 1e9 / (now - sourceSampleAt)}")
                                sourceSampleAt = now; sourceFrames = 0
                            }
                            CaptureBridge.onFrame?.invoke(frame.copy(id = ++sequence))
                        }
                    },
                    onError = { Handler(Looper.getMainLooper()).post { stopSelf() } })
            }.onFailure {
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    android.util.Log.d("BetterDeskPerf", "AVC fallback: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
            if (next != null) {
                if (display == null) {
                    display = projection!!.createVirtualDisplay("BetterDesk", next.width, next.height, resources.displayMetrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, next.surface, null, handler)
                } else {
                    display!!.resize(next.width, next.height, resources.displayMetrics.densityDpi)
                    display!!.surface = next.surface
                }
                val previous = encoder; encoder = next
                CaptureBridge.requestKeyFrame = { handler?.post { next.requestKeyFrame() } }
                handler!!.post { previous?.close() }
                reader?.close(); reader = null
                return
            }
            // A device without a compatible surface encoder retains the JPEG path.
            CaptureBridge.useAvc = false
        }
        val nextReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        nextReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (!CaptureBridge.active || now - lastFrame < 7) return@setOnImageAvailableListener
                lastFrame = now
                val started = System.nanoTime()
                val plane = image.planes[0]
                val paddedWidth = plane.rowStride / plane.pixelStride
                if (reusablePadded?.width != paddedWidth || reusablePadded?.height != image.height) {
                    reusablePadded?.recycle()
                    reusablePadded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                }
                val padded = reusablePadded!!
                padded.copyPixelsFromBuffer(plane.buffer)
                // Release the compositor buffer before CPU encoding. Only the latest
                // image is acquired, so the extra producer slot cannot queue stale video.
                val imageWidth = image.width
                val imageHeight = image.height
                image.close()
                val bitmap = if (paddedWidth == imageWidth) padded else {
                    if (reusableFrame?.width != imageWidth || reusableFrame?.height != imageHeight) {
                        reusableFrame?.recycle()
                        reusableFrame = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
                    }
                    reusableFrame!!.also { android.graphics.Canvas(it).drawBitmap(padded, 0f, 0f, null) }
                }
                val copied = System.nanoTime()
                encoded.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 65, encoded)
                copyNanos += copied - started
                encodeNanos += System.nanoTime() - copied
                sampledFrames++
                CaptureBridge.onFrame?.invoke(Packet.Frame(++sequence, bitmap.width, bitmap.height, encoded.toByteArray()))
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
        val oldEncoder = encoder; encoder = null
        handler?.post { oldEncoder?.close() }
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
        CaptureBridge.startAudio = null; CaptureBridge.stopAudio = null
        playbackCapture?.stop(); playbackCapture = null
        CaptureBridge.requestKeyFrame = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(1)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        handler?.post { encoder?.close(); encoder = null; reusablePadded?.recycle(); reusableFrame?.recycle(); reusablePadded = null; reusableFrame = null }
        handler?.post {
            if (sampledFrames > 0 && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                android.util.Log.d("BetterDeskPerf", "Capture frames=$sampledFrames copyMs=${copyNanos / sampledFrames / 1e6} encodeMs=${encodeNanos / sampledFrames / 1e6}")
            }
        }
        worker.quitSafely()
        if (wasActive) CaptureBridge.onStopped?.invoke()
        super.onDestroy()
    }
}
