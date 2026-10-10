package app.betterdesk

import android.app.NotificationManager
import android.app.Service
import androidx.core.app.NotificationCompat
import app.betterdesk.core.TransferPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map

/** Reuse the existing silent connection notification; never create a second notification. */
internal fun observeTransfers(service: Service, id: Int, builder: NotificationCompat.Builder, baseText: String): Job {
    return CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
        val session = Runtime.session ?: return@launch
        session.exchange.state.map { state ->
            val transfer = state.items.lastOrNull { it.active } ?: state.items.lastOrNull { it.incoming && it.phase == TransferPhase.OFFERED }
            val percent = transfer?.let { if (it.offer.bytes == 0L) 0 else (it.bytes * 100 / it.offer.bytes).toInt().coerceIn(0, 100) } ?: 0
            Triple(transfer?.phase, transfer?.incoming == true, percent / 5 * 5)
        }.distinctUntilChanged().collect { (phase, incoming, percent) ->
            val pt = (Runtime.platform?.languageTag ?: service.resources.configuration.locales[0].language) == "pt"
            val text = when (phase) {
                TransferPhase.OFFERED -> if (pt) "Ficheiros para receber. Toque para abrir." else "Files to receive. Tap to open."
                TransferPhase.CONNECTING -> if (pt) "A preparar transferência…" else "Preparing transfer…"
                TransferPhase.SENDING, TransferPhase.RECEIVING -> if (pt) (if (incoming) "A receber" else "A enviar") + " · $percent%" else (if (incoming) "Receiving" else "Sending") + " · $percent%"
                TransferPhase.VERIFYING -> if (pt) "A verificar ficheiros…" else "Checking files…"
                TransferPhase.SAVING -> if (pt) "A guardar ficheiros…" else "Saving files…"
                else -> ""
            }
            val progress = phase in setOf(TransferPhase.SENDING, TransferPhase.RECEIVING)
            builder.setContentText(if (text.isEmpty()) baseText else "$baseText · $text")
                .setProgress(if (progress) 100 else 0, if (progress) percent else 0, false)
            if (isActive) runCatching { service.getSystemService(NotificationManager::class.java).notify(id, builder.build()) }
        }
    }
}
