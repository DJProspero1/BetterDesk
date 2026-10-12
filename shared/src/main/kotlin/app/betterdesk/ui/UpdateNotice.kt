package app.betterdesk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.betterdesk.core.AppUpdater
import app.betterdesk.core.UpdateStage

/** Shown on the home screen only, so an update never interrupts a connection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun UpdateNotice(updater: AppUpdater, pt: Boolean) {
    val state by updater.state.collectAsState()
    fun t(en: String, portuguese: String) = if (pt) portuguese else en
    if (state.stage == UpdateStage.NONE) return
    Surface(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("update")) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            val message = when (state.stage) {
                UpdateStage.AVAILABLE -> t("BetterDesk ${state.version} is available.", "O BetterDesk ${state.version} está disponível.")
                UpdateStage.DOWNLOADING -> t("Downloading the update…", "A transferir a atualização…")
                UpdateStage.PERMISSION -> t("Android needs your permission once: turn on Allow from this source, then return here.", "O Android precisa da sua autorização uma vez: ative Permitir desta fonte e volte aqui.")
                UpdateStage.READY -> t("The update is ready. Installing restarts BetterDesk.", "A atualização está pronta. A instalação reinicia o BetterDesk.")
                UpdateStage.INSTALLING -> t("Installing the update…", "A instalar a atualização…")
                UpdateStage.FAILED -> t("The update didn't finish. BetterDesk is unchanged.", "A atualização não terminou. O BetterDesk não foi alterado.")
                UpdateStage.NONE -> ""
            }
            Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp)
            if (state.stage == UpdateStage.DOWNLOADING) LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp, bottom = 8.dp))
            FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state.stage) {
                    UpdateStage.AVAILABLE -> {
                        TextButton(onClick = updater::later) { Text(t("Later", "Mais tarde")) }
                        TextButton(onClick = updater::update) { Text(t("Update", "Atualizar")) }
                    }
                    UpdateStage.PERMISSION -> {
                        TextButton(onClick = updater::later) { Text(t("Later", "Mais tarde")) }
                        TextButton(onClick = updater::openInstallSettings) { Text(t("Open setting", "Abrir definição")) }
                    }
                    UpdateStage.READY -> TextButton(onClick = updater::update) { Text(t("Install update", "Instalar atualização")) }
                    UpdateStage.FAILED -> {
                        TextButton(onClick = updater::later) { Text(t("Dismiss", "Fechar")) }
                        TextButton(onClick = updater::update) { Text(t("Try again", "Tentar novamente")) }
                    }
                    else -> Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
