package app.betterdesk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.betterdesk.core.HelpSession
import app.betterdesk.ui.BetterDeskApp
import app.betterdesk.ui.BetterDeskTheme

object Runtime {
    var platform: AndroidPlatform? = null
    var session: HelpSession? = null
}

class MainActivity : ComponentActivity() {
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Runtime.platform?.projectionResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platform = Runtime.platform ?: AndroidPlatform(applicationContext).also { Runtime.platform = it }
        platform.attach(this) { projection.launch(it) }
        val session = Runtime.session ?: HelpSession(platform).also { Runtime.session = it }
        intent?.dataString?.let(session::acceptInvitation)
        setContent { BetterDeskTheme { BetterDeskApp(session) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { Runtime.session?.acceptInvitation(it) }
    }

    override fun onResume() {
        super.onResume()
        Runtime.session?.refreshPermissions()
        android.os.Handler(mainLooper).postDelayed({ if (!isFinishing) Runtime.session?.refreshPermissions() }, 400)
    }

    override fun onDestroy() {
        Runtime.platform?.detach(this)
        // Switching apps or pressing Back must not revoke a pending/active connection.
        // Explicit Stop, expiry and removal from Recents own connection teardown.
        super.onDestroy()
    }
}
