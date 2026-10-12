package app.betterdesk.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import app.betterdesk.core.AppUpdater
import app.betterdesk.core.HelpSession
import app.betterdesk.core.Stage
import app.betterdesk.ui.BetterDeskApp
import app.betterdesk.ui.BetterDeskTheme
import app.betterdesk.ui.BetterDeskIcon
import kotlinx.coroutines.*

fun main(args: Array<String>) {
    if (System.getProperty("os.name").startsWith("Windows")) {
        com.sun.jna.platform.win32.Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(com.sun.jna.WString("app.betterdesk.desktop"))
    }
    application {
    val session = remember { HelpSession(DesktopPlatform()) }
    val updater = remember {
        val idle = { session.state.value.stage in setOf(Stage.HOME, Stage.ENDED, Stage.ERROR) }
        // The new copy is already starting; close this one on the UI thread.
        DesktopUpdate { java.awt.EventQueue.invokeLater { session.dispose(); exitApplication() } }.takeIf { it.available }?.let { AppUpdater(it, idle) }
    }
    DisposableEffect(Unit) { onDispose { session.dispose(); updater?.dispose() } }
    LaunchedEffect(Unit) { args.firstOrNull { it.startsWith("betterdesk://") }?.let(session::acceptInvitation) }
    val windowState = rememberWindowState(width = 760.dp, height = 820.dp)
    var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
    Window(onCloseRequest = { session.dispose(); exitApplication() }, title = "BetterDesk", icon = rememberVectorPainter(BetterDeskIcon), state = windowState) {
        window.minimumSize = java.awt.Dimension(360, 560)
        val dropScope = rememberCoroutineScope()
        DisposableEffect(window) {
            val target = java.awt.dnd.DropTarget(window, java.awt.dnd.DnDConstants.ACTION_COPY, object : java.awt.dnd.DropTargetAdapter() {
                override fun dragEnter(event: java.awt.dnd.DropTargetDragEvent) {
                    if (session.exchange.state.value.connected && event.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) event.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
                    else event.rejectDrag()
                }
                override fun drop(event: java.awt.dnd.DropTargetDropEvent) {
                    if (!session.exchange.state.value.connected || !event.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) { event.rejectDrop(); return }
                    event.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                    val files = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        (event.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<java.io.File>).toList()
                    }.getOrNull()
                    event.dropComplete(files != null)
                    val connection = session.exchange.sessionToken
                    if (files != null) dropScope.launch {
                        try {
                            val local = withContext(Dispatchers.IO) { (session.platform.exchange as DesktopExchange).describe(files) }
                            session.exchange.offer(local, expectedSession = connection)
                        } catch (_: Exception) { session.exchange.notice("selectionFailed") }
                    }
                }
            })
            onDispose { target.isActive = false; window.dropTarget = null }
        }
        BetterDeskTheme { BetterDeskApp(session, updater) { enabled ->
            if (enabled && windowState.placement != WindowPlacement.Fullscreen) {
                previousPlacement = windowState.placement
                windowState.placement = WindowPlacement.Fullscreen
            } else if (!enabled && windowState.placement == WindowPlacement.Fullscreen) windowState.placement = previousPlacement
        } }
    }
    }
}
