package app.betterdesk

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import app.betterdesk.core.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

class AndroidPlatform(private val context: Context) : RemotePlatform {
    override val isAndroid = true
    override fun selectOutgoingCodec(codec: String) { CaptureBridge.useAvc = codec == "avc" }
    override fun requestKeyFrame() { CaptureBridge.requestKeyFrame?.invoke() }
    override fun captureResponsive() = CaptureBridge.active && System.nanoTime() - CaptureBridge.responsiveAt < 2_000_000_000
    override val defaultName = Build.MODEL.take(40)
    override val mayRestrictSettings = Build.VERSION.SDK_INT >= 33
    override fun openAppSettings() {
        activity.get()?.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
    }
    override fun keepConnectionAlive(active: Boolean) {
        val intent = Intent(context, ConnectionService::class.java)
        if (active) {
            if (!ConnectionService.active) ContextCompat.startForegroundService(context, intent)
        } else context.stopService(intent)
    }
    private var activity = WeakReference<Activity>(null)
    private var launch: ((Intent) -> Unit)? = null
    private var pending: CancellableContinuation<Boolean>? = null

    fun attach(value: Activity, request: (Intent) -> Unit) { activity = WeakReference(value); launch = request }
    fun detach(value: Activity) { if (activity.get() === value) { activity.clear(); launch = null } }
    override fun controlAvailable(): Boolean = ControlService.instance != null && (!CaptureBridge.active || CaptureBridge.fullDisplay)
    override fun openControlSettings() {
        val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, ControlService::class.java))
        runCatching { activity.get()?.startActivity(intent) }.onFailure {
            activity.get()?.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit): Boolean = suspendCancellableCoroutine { continuation ->
        pending = continuation
        CaptureBridge.onFrame = onFrame
        CaptureBridge.onStopped = onStopped
        CaptureBridge.onReady = { success -> pending?.let { if (it.isActive) it.resume(success) }; pending = null }
        continuation.invokeOnCancellation { pending = null }
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val request = launch
        if (request == null) { continuation.resume(false); pending = null }
        else request(if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent())
    }

    fun projectionResult(code: Int, data: Intent?) {
        if (pending?.isActive != true) return
        if (code != Activity.RESULT_OK || data == null) {
            pending?.resume(false); pending = null; return
        }
        val intent = Intent(context, ScreenShareService::class.java).putExtra("resultCode", code).putExtra("projection", data)
        runCatching { ContextCompat.startForegroundService(context, intent) }.onFailure {
            pending?.resume(false); pending = null
        }
    }

    override fun stopSharing() {
        if (CaptureBridge.active && context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Runtime.session?.performance?.snapshot()?.let {
                android.util.Log.d("BetterDeskPerf", "Source drops queue=${it.queueDrops} window=${it.windowDrops} recovery=${it.recoveryDrops}")
            }
        }
        CaptureBridge.active = false
        CaptureBridge.onFrame = null; CaptureBridge.onReady = null; CaptureBridge.onStopped = null
        CaptureBridge.requestKeyFrame = null
        pending?.cancel(); pending = null
        context.stopService(Intent(context, ScreenShareService::class.java))
    }

    override fun applyInput(input: Message) { if (CaptureBridge.active) ControlService.instance?.apply(input) }

    override fun decode(frame: Packet.Frame): ImageBitmap? = runCatching {
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, dimensions)
        check(dimensions.outWidth == frame.width && dimensions.outHeight == frame.height)
        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)?.asImageBitmap()
    }.getOrNull()
}
