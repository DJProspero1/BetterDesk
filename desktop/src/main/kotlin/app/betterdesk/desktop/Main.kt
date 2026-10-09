package app.betterdesk.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import app.betterdesk.core.HelpSession
import app.betterdesk.ui.BetterDeskApp
import app.betterdesk.ui.BetterDeskTheme
import app.betterdesk.ui.BetterDeskIcon

fun main(args: Array<String>) {
    if (System.getProperty("os.name").startsWith("Windows")) {
        com.sun.jna.platform.win32.Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(com.sun.jna.WString("app.betterdesk.desktop"))
    }
    application {
    val session = remember { HelpSession(DesktopPlatform()) }
    DisposableEffect(Unit) { onDispose { session.dispose() } }
    LaunchedEffect(Unit) { args.firstOrNull { it.startsWith("betterdesk://") }?.let(session::acceptInvitation) }
    val windowState = rememberWindowState(width = 760.dp, height = 820.dp)
    var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
    Window(onCloseRequest = { session.dispose(); exitApplication() }, title = "BetterDesk", icon = rememberVectorPainter(BetterDeskIcon), state = windowState) {
        window.minimumSize = java.awt.Dimension(360, 560)
        BetterDeskTheme { BetterDeskApp(session) { enabled ->
            if (enabled && windowState.placement != WindowPlacement.Fullscreen) {
                previousPlacement = windowState.placement
                windowState.placement = WindowPlacement.Fullscreen
            } else if (!enabled && windowState.placement == WindowPlacement.Fullscreen) windowState.placement = previousPlacement
        } }
    }
    }
}
