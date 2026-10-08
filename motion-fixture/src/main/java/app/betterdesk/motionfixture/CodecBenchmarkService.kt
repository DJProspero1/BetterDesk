package app.betterdesk.motionfixture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.Surface

/** Measures direct projection -> AVC surface encoding. Discards every output, never records a screen. */
class CodecBenchmarkService : Service() {
    private val thread = HandlerThread("BetterDesk codec experiment")
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var start = 0L
    private var frames = 0L
    private var bytes = 0L
    private var lastPts = -1L
    private var codecLabel = ""
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (intent == null || projection != null) return START_NOT_STICKY
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("benchmark", "Local capture test", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "benchmark").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("BetterDesk local codec test").setContentText("Stops automatically after 18 seconds")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(2, notification)
        thread.start(); handler = Handler(thread.looper)
        @Suppress("DEPRECATION") val consent = intent.getParcelableExtra<Intent>("consent")!!
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("result", 0), consent)
        projection!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, Handler(mainLooper))
        handler.post {
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 576, 1280).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) ?: error("No AVC surface encoder")
                val encoder = MediaCodec.createByCodecName(name).also { codec = it }
                codecLabel = "$name hardware=${if (Build.VERSION.SDK_INT >= 29) encoder.codecInfo.isHardwareAccelerated else "unknown"}"
                encoder.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
                    override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) { Log.e("BetterDeskCodec", "Codec error ${error.errorCode}"); stopSelf() }
                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        if (System.nanoTime() >= start && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.presentationTimeUs > lastPts) {
                            frames++; bytes += info.size; lastPts = info.presentationTimeUs
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }, handler)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = encoder.createInputSurface()
                encoder.start()
                display = projection!!.createVirtualDisplay("BetterDesk codec experiment", 576, 1280, resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler)
                start = System.nanoTime() + 3_000_000_000
                handler.postDelayed({
                    val seconds = (System.nanoTime() - start) / 1e9
                    Log.i("BetterDeskCodec", "$codecLabel frames=$frames fps=${frames / seconds} Mbps=${bytes * 8 / seconds / 1e6} seconds=$seconds")
                    stopSelf()
                }, 18_000)
            } catch (error: Exception) { Log.e("BetterDeskCodec", "Setup failed: ${error.javaClass.simpleName}"); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (::handler.isInitialized) handler.post {
            display?.release(); display = null
            runCatching { codec?.stop() }; codec?.release(); codec = null
            surface?.release(); surface = null
            projection?.stop(); projection = null
            thread.quitSafely()
        }
        super.onDestroy()
    }
}
