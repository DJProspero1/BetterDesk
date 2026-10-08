package app.betterdesk.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.betterdesk.core.HelpSession
import app.betterdesk.ui.BetterDeskApp
import app.betterdesk.ui.BetterDeskTheme

fun main(args: Array<String>) = application {
    val session = remember { HelpSession(DesktopPlatform()) }
    DisposableEffect(Unit) { onDispose { session.dispose() } }
    LaunchedEffect(Unit) { args.firstOrNull { it.startsWith("betterdesk://") }?.let(session::acceptInvitation) }
    Window(onCloseRequest = { session.dispose(); exitApplication() }, title = "BetterDesk", state = rememberWindowState(width = 760.dp, height = 820.dp)) {
        window.minimumSize = java.awt.Dimension(360, 560)
        BetterDeskTheme { BetterDeskApp(session) }
    }
}
