package app.betterdesk.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.betterdesk.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

@Composable internal fun ExchangeNotice(session: HelpSession, pt: Boolean) {
    val notices = remember(session) { session.exchange.state.map { it.notice }.distinctUntilChanged() }
    val notice by notices.collectAsState("")
    if (notice.isNotEmpty()) {
        Surface(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(noticeText(notice, pt), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp)
                TextButton(onClick = session.exchange::dismissNotice) { Text(if (pt) "Fechar" else "Dismiss") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ExchangePanel(session: HelpSession, pt: Boolean, controls: Boolean, modifier: Modifier = Modifier) {
    val state by session.exchange.state.collectAsState()
    var details by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val duration = if (session.platform.reduceMotion) 0 else 180
    fun t(en: String, portuguese: String) = if (pt) portuguese else en
    LaunchedEffect(state.items) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val attention = state.items.lastOrNull { it.incoming && it.phase == TransferPhase.OFFERED && it.offer.kind != "clipboard" }
        ?: state.items.lastOrNull { it.active }
        ?: state.items.lastOrNull { it.phase == TransferPhase.FAILED }
        ?: state.items.lastOrNull { it.phase == TransferPhase.OFFERED }
        ?: state.items.lastOrNull { now - it.updatedAt < 6000 }
    Column(modifier) {
        if (controls && state.connected) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilledTonalButton(onClick = session.exchange::pickFiles, modifier = Modifier.heightIn(min = 48.dp)) { Text(t("Send files", "Enviar ficheiros")) }
                TextButton(onClick = { session.exchange.setClipboard(!state.clipboard) }) {
                    Text(if (state.clipboard) t("Clipboard on", "Área de transferência ativa") else t("Clipboard off", "Área de transferência desativada"))
                }
                if (state.clipboard && session.platform.isAndroid) TextButton(onClick = session.exchange::sendClipboard, enabled = state.peerClipboard) { Text(t("Send clipboard", "Enviar conteúdo copiado")) }
                if (state.items.isNotEmpty()) TextButton(onClick = { details = !details }) { Text(if (details) t("Hide transfers", "Ocultar transferências") else t("Transfers", "Transferências")) }
            }
        }
        AnimatedVisibility(attention != null || state.notice.isNotEmpty() || (details && state.items.isNotEmpty()),
            enter = fadeIn(tween(duration)) + slideInVertically(tween(duration, easing = CubicBezierEasing(.22f, 1f, .36f, 1f))) { it / 8 },
            exit = fadeOut(tween(duration))) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (state.notice.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(noticeText(state.notice, pt), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = session.exchange::dismissNotice) { Text(t("Dismiss", "Fechar")) }
                    }
                    if (details) {
                        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.items.asReversed().forEach { item -> key(item.offer.id) { TransferRow(session.exchange, item, pt, duration) } }
                        }
                        TextButton(onClick = { details = false }) { Text(t("Hide details", "Ocultar detalhes")) }
                    } else attention?.let { item ->
                        key(item.offer.id) { TransferRow(session.exchange, item, pt, duration) }
                        if (state.items.count { it.active || it.phase == TransferPhase.OFFERED || it.phase == TransferPhase.FAILED } > 1) {
                            TextButton(onClick = { details = true }) { Text(t("All transfers", "Todas as transferências")) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TransferRow(exchange: SessionExchange, item: TransferItem, pt: Boolean, duration: Int) {
    fun t(en: String, portuguese: String) = if (pt) portuguese else en
    val name = if (item.offer.fileCount == 1) item.offer.entries.first { !it.directory }.path.substringAfterLast('/')
        else t("${item.offer.fileCount} files", "${item.offer.fileCount} ficheiros")
    val label = when (item.phase) {
        TransferPhase.OFFERED -> if (!item.incoming) t("Waiting for the other device", "A aguardar o outro dispositivo")
            else if (item.offer.kind == "clipboard") t("Ready to paste", "Pronto a colar") else t("Files to receive", "Ficheiros para receber")
        TransferPhase.CONNECTING -> t("Connecting…", "A ligar…")
        TransferPhase.SENDING -> t("Sending", "A enviar")
        TransferPhase.RECEIVING -> t("Receiving", "A receber")
        TransferPhase.VERIFYING -> t("Checking files…", "A verificar ficheiros…")
        TransferPhase.SAVING -> t("Saving…", "A guardar…")
        TransferPhase.DONE -> if (!item.incoming) t("Sent", "Enviado") else if (item.location.isNotEmpty()) t("Saved", "Guardado") else t("Received", "Recebido")
        TransferPhase.CANCELED -> t("Canceled", "Cancelado")
        TransferPhase.FAILED -> when (item.error) {
            "saveFailed" -> t("Couldn't save. Try again.", "Não foi possível guardar. Tente novamente.")
            "saveCanceled" -> t("Choose a folder to finish saving", "Escolha uma pasta para guardar")
            else -> t("Transfer interrupted", "Transferência interrompida")
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(label, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 12.sp,
                    color = if (item.phase == TransferPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.active || item.phase == TransferPhase.OFFERED) TextButton(onClick = { exchange.cancel(item.offer.id) }) {
                Text(if (item.incoming && item.phase == TransferPhase.OFFERED) t("Decline", "Recusar") else t("Cancel", "Cancelar"))
            }
            if (item.phase == TransferPhase.FAILED && item.incoming) TextButton(onClick = { exchange.retry(item.offer.id) }) { Text(t("Retry", "Tentar novamente")) }
            if (item.phase == TransferPhase.DONE && item.location.isNotEmpty()) TextButton(onClick = { exchange.open(item.offer.id) }) { Text(t("Open folder", "Abrir pasta")) }
        }
        if (item.active) {
            Spacer(Modifier.height(8.dp))
            if (item.phase == TransferPhase.CONNECTING || item.phase == TransferPhase.SAVING) {
                if (duration == 0) Text(t("Please wait", "Aguarde"), fontSize = 12.sp)
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            } else {
                val progress by animateFloatAsState(if (item.offer.bytes == 0L) 1f else (item.bytes.toDouble() / item.offer.bytes).toFloat().coerceIn(0f, 1f), tween(duration), label = "Transfer progress")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatBytes(item.bytes, pt)} / ${formatBytes(item.offer.bytes, pt)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.bytesPerSecond > 0 && item.phase != TransferPhase.VERIFYING) Text("${formatBytes(item.bytesPerSecond, pt)}/s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (item.incoming && item.phase == TransferPhase.OFFERED) {
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilledTonalButton(onClick = { exchange.receive(item.offer.id) }) { Text(t("Save files", "Guardar ficheiros")) }
                Text(formatBytes(item.offer.bytes, pt), Modifier.align(Alignment.CenterVertically), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatBytes(bytes: Long, pt: Boolean): String {
    val locale = if (pt) Locale.forLanguageTag("pt-PT") else Locale.ENGLISH
    return when {
        bytes >= 1024L * 1024 * 1024 -> String.format(locale, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format(locale, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

internal fun noticeText(key: String, pt: Boolean): String {
    fun t(en: String, portuguese: String) = if (pt) portuguese else en
    return when (key) {
        "clipboardSent" -> t("Clipboard sent", "Conteúdo copiado enviado")
        "clipboardDisabled" -> t("Turn on Clipboard on both devices to send text.", "Ative a área de transferência nos dois dispositivos para enviar texto.")
        "clipboardReceived" -> t("Ready to paste", "Pronto a colar")
        "emptyClipboard" -> t("Copy something first, then try again here.", "Copie algo primeiro e tente novamente aqui.")
        "clipboardFailed" -> t("Clipboard unavailable. Use Send files instead.", "Área de transferência indisponível. Utilize Enviar ficheiros.")
        "clipboardTooLarge" -> t("Too large for the clipboard. Use Send files.", "Demasiado grande para copiar. Utilize Enviar ficheiros.")
        "selectionLimit" -> t("Choose up to 128 files and folders, up to 1 GB. Use ordinary filenames without special characters.", "Escolha até 128 ficheiros e pastas, até 1 GB. Utilize nomes comuns, sem caracteres especiais.")
        "selectionFailed" -> t("Couldn't open those files. Choose them again.", "Não foi possível abrir os ficheiros. Escolha-os novamente.")
        "connectFirst" -> t("Connect to a device first, then send your files.", "Ligue-se primeiro a um dispositivo e envie os ficheiros.")
        "busy" -> t("Finish or cancel the current transfer first.", "Conclua ou cancele primeiro a transferência atual.")
        "receiveLimit" -> t("Receiving limit reached. Finish your transfers, then reconnect.", "Limite de receção atingido. Conclua as transferências e volte a ligar.")
        "retryOtherDevice" -> t("Choose Retry on the receiving device.", "Escolha Tentar novamente no dispositivo de destino.")
        "openFailed" -> t("Open Downloads to find your files.", "Abra Transferências para encontrar os ficheiros.")
        else -> t("Transfer unavailable. Try again.", "Transferência indisponível. Tente novamente.")
    }
}
