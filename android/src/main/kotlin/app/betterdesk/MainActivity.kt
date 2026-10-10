package app.betterdesk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.betterdesk.core.HelpSession
import app.betterdesk.ui.BetterDeskApp
import app.betterdesk.ui.BetterDeskTheme
import kotlinx.coroutines.*

object Runtime {
    var platform: AndroidPlatform? = null
    var session: HelpSession? = null
}

class MainActivity : ComponentActivity() {
    private val fileResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Runtime.platform?.exchange?.result(result.resultCode, result.data)
    }
    private val shareScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingProjection: Intent? = null
    private val sharingPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // A user's notification choice never silently grants screen capture.
        // The separate, genuine MediaProjection prompt still follows.
        pendingProjection?.let(projection::launch)
        pendingProjection = null
    }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Runtime.platform?.projectionResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        @Suppress("DEPRECATION")
        pendingProjection = savedInstanceState?.getParcelable("pendingProjection")
        val platform = Runtime.platform ?: AndroidPlatform(applicationContext).also { Runtime.platform = it }
        platform.attach(this) { request ->
            val permissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.POST_NOTIFICATIONS
            // Android calls this audio permission even for playback-only MediaProjection capture.
            if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.RECORD_AUDIO
            if (permissions.isNotEmpty()) {
                pendingProjection = request
                sharingPermissions.launch(permissions.toTypedArray())
            } else projection.launch(request)
        }
        val session = Runtime.session ?: HelpSession(platform).also { Runtime.session = it }
        platform.exchange.attach(this, fileResult::launch)
        if (intent?.action == Intent.ACTION_VIEW && intent?.data?.scheme == "betterdesk") intent?.dataString?.let(session::acceptInvitation)
        if (savedInstanceState == null) receiveSharedIntent(intent)
        setContent { BetterDeskTheme { BetterDeskApp(session, ::setFullScreen) } }
    }

    private fun setFullScreen(enabled: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "betterdesk") intent.dataString?.let { Runtime.session?.acceptInvitation(it) }
        receiveSharedIntent(intent)
    }

    private fun receiveSharedIntent(intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val session = Runtime.session ?: return
        val connection = session.exchange.sessionToken
        if (!session.exchange.state.value.connected) { session.exchange.notice("connectFirst"); return }
        @Suppress("DEPRECATION")
        val uris = if (intent!!.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
        else listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        shareScope.launch {
            try {
                if (uris.isNotEmpty()) {
                    val files = withContext(Dispatchers.IO) { Runtime.platform!!.exchange.describe(uris) }
                    session.exchange.offer(files, expectedSession = connection)
                } else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let {
                    if (session.exchange.sessionToken == connection && session.exchange.state.value.connected) {
                        session.exchange.shareText(it, expectedSession = connection)
                    }
                }
            } catch (_: CancellationException) { }
            catch (_: Exception) { session.exchange.notice("selectionFailed") }
        }
        // A rotation must not resend an already-consumed share.
        intent.action = Intent.ACTION_MAIN
    }

    override fun onResume() {
        super.onResume()
        Runtime.session?.refreshPermissions()
        android.os.Handler(mainLooper).postDelayed({ if (!isFinishing) Runtime.session?.refreshPermissions() }, 400)
    }

    override fun onDestroy() {
        Runtime.platform?.detach(this)
        Runtime.platform?.exchange?.detach(this)
        shareScope.cancel()
        // Switching apps or pressing Back must not revoke a pending/active connection.
        // Explicit Stop, expiry and removal from Recents own connection teardown.
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingProjection?.let { outState.putParcelable("pendingProjection", it) }
        super.onSaveInstanceState(outState)
    }
}
