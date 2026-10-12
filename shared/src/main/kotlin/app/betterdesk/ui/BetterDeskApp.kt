package app.betterdesk.ui

import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.betterdesk.core.*
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun BetterDeskTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFEDEDED), onPrimary = Color(0xFF111111), background = Color(0xFF0A0A0A),
        surface = Color(0xFF151515), onSurface = Color(0xFFEDEDED), onBackground = Color(0xFFEDEDED),
        surfaceVariant = Color(0xFF252525), onSurfaceVariant = Color(0xFFBDBDBD),
        secondary = Color(0xFFCFCFCF), onSecondary = Color(0xFF171717),
        primaryContainer = Color(0xFF303030), onPrimaryContainer = Color(0xFFEDEDED),
        secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color(0xFFEDEDED),
        outline = Color(0xFF797979), outlineVariant = Color(0xFF3A3A3A), surfaceTint = Color.Transparent
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)), content = content)
}

@Composable
fun BetterDeskApp(session: HelpSession, updater: AppUpdater? = null, onFullScreenChange: (Boolean) -> Unit = {}) {
    val state by session.state.collectAsState()
    var portuguese by rememberSaveable { mutableStateOf((session.platform.languageTag ?: Locale.getDefault().language) == "pt") }
    val viewing = state.stage == Stage.VIEWING
    var fullScreen by rememberSaveable(viewing) { mutableStateOf(viewing) }
    val fullScreenHandler by rememberUpdatedState(onFullScreenChange)
    LaunchedEffect(fullScreen) { fullScreenHandler(fullScreen) }
    DisposableEffect(Unit) { onDispose { fullScreenHandler(false) } }
    fun t(en: String, pt: String) = if (portuguese) pt else en
    val clipboard = LocalClipboardManager.current
    var deviceCode by remember { mutableStateOf("") }
    var restricted by remember(state.stage) { mutableStateOf(false) }
    var appSettingsOpened by remember(state.stage) { mutableStateOf(false) }
    var copied by remember(state.stage) { mutableStateOf(false) }
    LaunchedEffect(state.stage) {
        if (state.stage == Stage.HOME) session.prepareDeviceCode()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (viewing) {
            RemoteViewer(session, state, portuguese, Modifier.fillMaxSize(), fullScreen) { fullScreen = it }
        } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(rememberVectorPainter(BetterDeskIcon), null, Modifier.size(36.dp))
                Text("BetterDesk", Modifier.padding(start = 10.dp).weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                LanguageButton(portuguese) {
                    portuguese = !portuguese
                    session.platform.languageTag = if (portuguese) "pt" else "en"
                }
            }
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                        if (state.stage != Stage.SHARING) ExchangeNotice(session, portuguese)
                        when (state.stage) {
                            Stage.HOME -> {
                                updater?.let { UpdateNotice(it, portuguese) }
                                Text(t("This device", "Este dispositivo"), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(session.platform.defaultName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(12.dp))
                                if (state.deviceCode.isNotEmpty()) {
                                    Text(state.deviceCode.chunked(4).joinToString(" "), fontSize = if (state.deviceCode.length == 12) 30.sp else 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(state.deviceCode)); copied = true }) {
                                        Text(if (copied) t("Code copied", "Código copiado") else t("Copy code", "Copiar código"))
                                    }
                                    Text(t("Share this code. You decide who connects.", "Partilhe este código. Decide quem se liga."), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (state.deviceCode.length == 8) Text(t("Local network only. Check your internet connection.", "Apenas na rede local. Verifique a ligação à Internet."), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (state.codeLoading) {
                                    Text(t("Getting a code…", "A obter um código…"), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    TextButton(onClick = { session.prepareDeviceCode() }) { Text(t("Get a code", "Obter código")) }
                                }
                                Spacer(Modifier.height(40.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(32.dp))
                                Text(t("Connect to a device", "Ligar a um dispositivo"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(16.dp))
                                OutlinedTextField(deviceCode, { deviceCode = Pairing.normalize(it).filter { c -> c in '0'..'9' }.take(12) }, Modifier.fillMaxWidth(),
                                    label = { Text(t("Device code", "Código do dispositivo")) }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                Spacer(Modifier.height(16.dp))
                                Primary(t("Connect", "Ligar"), Pairing.validDeviceCode(deviceCode) && deviceCode != state.deviceCode) { session.connectCode(deviceCode) }
                            }
                            Stage.INVITATION -> {
                                Heading(t("Allow ${state.peer} to connect?", "Permitir a ligação de ${state.peer}?"), t("Share your screen, device sound and control. You can also copy text and send files. Your microphone stays off.", "Partilhe o ecrã, o som do dispositivo e o controlo. Também pode copiar texto e enviar ficheiros. O microfone fica desligado."))
                                Primary(t("Allow connection", "Permitir ligação")) { session.allowHelp() }
                                TextButton(onClick = { session.home() }) { Text(t("Decline", "Recusar")) }
                            }
                            Stage.PREPARING -> {
                                Heading(t("Connecting…", "A ligar…"), t("Finding the other device.", "A procurar o outro dispositivo."))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                TextButton(onClick = { session.home() }) { Text(t("Cancel", "Cancelar")) }
                            }
                            Stage.WAITING -> {
                                Heading(t("Waiting for the other device…", "A aguardar o outro dispositivo…"), t("The connection starts when it is approved there. You can switch apps while you wait.", "A ligação começa quando for autorizada no outro dispositivo. Pode mudar de aplicação enquanto espera."))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                TextButton(onClick = { session.home() }) { Text(t("Cancel", "Cancelar")) }
                            }
                            Stage.CONNECTING -> {
                                Heading(t("Getting connected…", "A estabelecer ligação…"), t("You can switch apps while you wait.", "Pode mudar de aplicação enquanto espera."))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                TextButton(onClick = { session.stop() }) { Text(t("Cancel", "Cancelar")) }
                            }
                            Stage.CONTROL_PERMISSION -> {
                                if (restricted) {
                                    Heading(t("Unblock the setting", "Desbloquear a definição"), t("Android may block control for apps installed from an APK. Only continue if you trust BetterDesk.", "O Android pode bloquear o controlo em aplicações instaladas por APK. Continue apenas se confia no BetterDesk."))
                                    PermissionStep("1", t("Open app info below.", "Abra as informações da aplicação abaixo."))
                                    PermissionStep("2", t("Tap ⋮, then Allow restricted settings.", "Toque em ⋮ e em Permitir definições restritas."))
                                    PermissionStep("3", t("Return here. Then enable control.", "Volte aqui. Depois ative o controlo."))
                                    Spacer(Modifier.height(20.dp))
                                    Primary(t("Open app info", "Abrir informações da aplicação")) { appSettingsOpened = true; session.openRestrictedSettings() }
                                    if (appSettingsOpened) TextButton(onClick = { restricted = false; session.enableControl() }) { Text(t("Continue to control settings", "Continuar para as definições de controlo")) }
                                    TextButton(onClick = { restricted = false }) { Text(t("I don't see that option", "Não vejo essa opção")) }
                                } else {
                                    Heading(t("Enable control", "Ativar controlo"), t("This lets the connected person tap and type. Android needs you to approve it once.", "Permite à pessoa ligada tocar e escrever. O Android precisa que autorize esta opção uma vez."))
                                    PermissionStep("1", t("Select BetterDesk in Accessibility.", "Escolha BetterDesk em Acessibilidade."))
                                    PermissionStep("2", t("Turn on Use BetterDesk and confirm.", "Ative Utilizar BetterDesk e confirme."))
                                    PermissionStep("3", t("Return here to continue.", "Volte aqui para continuar."))
                                    if (state.permissionReturned) {
                                        Spacer(Modifier.height(12.dp))
                                        Text(t("Control is still off.", "O controlo continua desativado."), color = MaterialTheme.colorScheme.error)
                                    }
                                    Spacer(Modifier.height(20.dp))
                                    Primary(t("Open control settings", "Abrir definições de controlo")) { session.enableControl() }
                                    if (session.platform.mayRestrictSettings) TextButton(onClick = { restricted = true }) { Text(t("Android blocked this?", "O Android bloqueou esta opção?")) }
                                }
                                TextButton(onClick = { session.shareWithoutControl() }) { Text(t("Share my screen only", "Partilhar apenas o ecrã")) }
                                TextButton(onClick = { session.home() }) { Text(t("Cancel", "Cancelar")) }
                            }
                            Stage.SHARING -> {
                                Heading(t("Connected to ${state.peer}", "Ligado a ${state.peer}"), if (state.remoteControl) t("Your screen is shared. They can tap and type. Stay with your device.", "O ecrã está partilhado. A pessoa pode tocar e escrever. Fique junto do dispositivo.") else t("Your screen is shared. Only you can tap and type.", "O ecrã está partilhado. Só o utilizador pode tocar e escrever."))
                                Primary(t("Disconnect", "Desligar")) { session.stop() }
                                SessionSwitch(session, state, portuguese)
                                ExchangePanel(session, portuguese, controls = true, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                                Text(if (state.audioAvailable) t("Device sound is shared", "O som do dispositivo está partilhado") else t("Device sound is unavailable", "O som do dispositivo está indisponível"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(16.dp))
                                Text(t("You can continue using your device.", "Pode continuar a utilizar o dispositivo."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Stage.ENDED -> {
                                Heading(t("Disconnected", "Ligação terminada"), t("Your screen is no longer shared. This connection is closed.", "O ecrã já não está partilhado. A ligação foi encerrada."))
                                Primary(t("Done", "Concluir")) { deviceCode = ""; session.home() }
                            }
                            Stage.ERROR -> {
                                val explanation = when (state.error) {
                                    "code" -> t("Check the code and keep BetterDesk open on the other device. Ask for a new code if it has expired.", "Confirme o código e mantenha o BetterDesk aberto no outro dispositivo. Peça um novo código se tiver expirado.")
                                    "discovery" -> t("Another copy of BetterDesk may be open. Close it and try again.", "Pode estar aberta outra cópia do BetterDesk. Feche-a e tente novamente.")
                                    "network" -> t("Check your internet connection, then try again. BetterDesk may be temporarily unavailable.", "Verifique a ligação à Internet e tente novamente. O BetterDesk pode estar temporariamente indisponível.")
                                    else -> t("Sharing has stopped. Check the connection, then reconnect with a new code.", "A partilha parou. Verifique a ligação e volte a ligar com um novo código.")
                                }
                                Heading(t("Couldn't connect", "Não foi possível ligar"), explanation)
                                Primary(t("Try again", "Tentar novamente")) { session.home() }
                            }
                            Stage.VIEWING -> Unit
                        }
                    }
                }
                Text(t("Your permission. Every connection.", "A sua autorização. Em cada ligação."), Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        }
    }
}

@Composable private fun SessionSwitch(session: HelpSession, state: HelpState, pt: Boolean) {
    fun t(en: String, portuguese: String) = if (pt) portuguese else en
    when (state.switchState) {
        "incoming" -> Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(t("Switch screens?", "Trocar ecrãs?"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (state.stage == Stage.VIEWING) t("Your screen and device sound will be shared. Your microphone stays off.", "O seu ecrã e som serão partilhados. O microfone fica desligado.")
                else t("You will see the other device's screen.", "Vai ver o ecrã do outro dispositivo."), fontSize = 15.sp)
            Row {
                Button(onClick = session::acceptSwitch) { Text(t("Allow switch", "Permitir troca")) }
                TextButton(onClick = session::cancelSwitch) { Text(t("Decline", "Recusar")) }
            }
        }
        "sent" -> Column(Modifier.padding(12.dp)) {
            Text(t("Waiting for the other device…", "A aguardar o outro dispositivo…"), fontSize = 15.sp)
            TextButton(onClick = session::cancelSwitch) { Text(t("Cancel switch", "Cancelar troca")) }
        }
        "switching" -> Text(t("Switching screens…", "A trocar ecrãs…"), Modifier.padding(12.dp), fontSize = 15.sp)
        else -> TextButton(onClick = session::requestSwitch) { Text(t("Switch screens", "Trocar ecrãs")) }
    }
}

@Composable private fun PermissionStep(number: String, instruction: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(number, Modifier.width(28.dp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(instruction, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 23.sp)
    }
}

@Composable private fun Heading(title: String, body: String) {
    Text(title, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(16.dp))
    Text(body, fontSize = 17.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(32.dp))
}

@Composable private fun Primary(label: String, enabled: Boolean = true, action: () -> Unit) {
    Button(action, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(16.dp)) {
        Text(label, fontSize = 17.sp, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RemoteViewer(session: HelpSession, state: HelpState, pt: Boolean, modifier: Modifier, fullScreen: Boolean, onFullScreenChange: (Boolean) -> Unit) {
    fun t(en: String, portuguese: String) = if (pt) portuguese else en
    var typed by remember(session) { mutableStateOf("") }
    var inputOpen by rememberSaveable { mutableStateOf(false) }
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
    var fresh by remember { mutableStateOf(true) }
    val duration = if (session.platform.reduceMotion) 0 else 180
    val inputEnabled = state.remoteControl && fresh && state.switchState.isEmpty()
    val focusRequester = remember { FocusRequester() }
    val screenFocus = remember { FocusRequester() }
    val exitFocus = remember { FocusRequester() }
    var keyboardFocused by remember { mutableStateOf(false) }
    var composition by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    fun releaseKeyboard() { session.releaseKeyboard(); focusManager.clearFocus(); keyboard?.hide(); exitFocus.requestFocus() }
    LaunchedEffect(inputOpen) { if (inputOpen) focusRequester.requestFocus() }
    LaunchedEffect(inputEnabled, windowFocused) { if ((!inputEnabled || !windowFocused) && keyboardFocused) releaseKeyboard() }
    LaunchedEffect(state.frameAt) {
        fresh = true; delay(2000); fresh = false
    }
    Box(modifier.background(Color.Black).then(if (fullScreen) Modifier else Modifier.safeDrawingPadding()).testTag("remote-viewport")) {
        RemoteKeyboard(session, inputEnabled, screenFocus, Modifier.size(1.dp),
            onFocus = { keyboardFocused = it }, onComposition = { composition = it }, release = ::releaseKeyboard)
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            state.image?.let { image ->
                val ratio = image.width.toFloat() / image.height
                val width = minOf(maxWidth, maxHeight * ratio)
                val height = width / ratio
                Image(image, t("Shared screen", "Ecrã partilhado"), Modifier.size(width, height).testTag("remote-image")
                    .drawWithContent {
                    drawContent()
                    session.performance.presented(state.frame)
                }
                    .pointerInput(session, inputEnabled) {
                        detectTapGestures(onTap = { if (inputEnabled) { inputOpen = false; screenFocus.requestFocus() }; session.input("tap", (it.x / size.width).coerceIn(0f, 1f), (it.y / size.height).coerceIn(0f, 1f)) }, onLongPress = { if (inputEnabled) screenFocus.requestFocus(); session.input("right", (it.x / size.width).coerceIn(0f, 1f), (it.y / size.height).coerceIn(0f, 1f)) })
                    }.pointerInput(session) {
                        var start = Offset.Zero; var end = Offset.Zero
                        detectDragGestures(onDragStart = { start = it; end = it }, onDragEnd = {
                            session.input("swipe", (start.x / size.width).coerceIn(0f, 1f), (start.y / size.height).coerceIn(0f, 1f), (end.x / size.width).coerceIn(0f, 1f), (end.y / size.height).coerceIn(0f, 1f))
                        }) { change, _ -> change.consume(); end = change.position }
                    })
            }
        }
        if (!controlsOpen && state.switchState.isEmpty()) {
            Column(Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(12.dp).widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExchangePanel(session, pt, controls = false)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = session.exchange::pickFiles, modifier = Modifier.heightIn(min = 48.dp)) { Text(t("Send files", "Enviar ficheiros")) }
                FilledTonalButton(onClick = session::requestSwitch, modifier = Modifier.heightIn(min = 48.dp)) { Text(t("Switch screens", "Trocar ecrãs")) }
                FilledTonalButton(onClick = { controlsOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(t("Controls", "Controlos")) }
            }
            }
        }
        AnimatedVisibility(controlsOpen || state.switchState.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding().padding(8.dp).widthIn(max = 760.dp).fillMaxWidth(),
            enter = fadeIn(tween(duration)) + slideInVertically(tween(duration, easing = CubicBezierEasing(.22f, 1f, .36f, 1f))) { it / 6 },
            exit = fadeOut(tween(duration * 3 / 4)) + slideOutVertically(tween(duration * 3 / 4)) { it / 6 }) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (!fresh) t("Waiting for the screen…", "A aguardar o ecrã…") else if (state.remoteControl) t("You can control this screen", "Pode controlar este ecrã") else t("Viewing only", "Apenas visualização"), Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 14.sp)
            TextButton(onClick = { controlsOpen = false; inputOpen = false; keyboard?.hide(); if (inputEnabled) screenFocus.requestFocus() }) { Text(t("Hide", "Ocultar")) }
            TextButton(onClick = { session.stop() }) { Text(t("Disconnect", "Desligar")) }
        }
        if (state.switchState.isNotEmpty()) SessionSwitch(session, state, pt)
        ExchangePanel(session, pt, controls = true, modifier = Modifier.padding(horizontal = 8.dp))
        if (!inputOpen) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            if (state.switchState.isEmpty()) TextButton(onClick = session::requestSwitch) { Text(t("Switch screens", "Trocar ecrãs")) }
            TextButton(onClick = session::toggleSound, enabled = state.audioAvailable) { Text(if (!state.audioAvailable) t("Sound unavailable", "Som indisponível") else if (state.muted) t("Unmute", "Ligar som") else t("Mute", "Silenciar")) }
            TextButton(onClick = { inputOpen = !inputOpen }, enabled = inputEnabled) { Text(t("Type", "Escrever")) }
            TextButton(onClick = { session.input("copy") }, enabled = inputEnabled) { Text(t("Copy selection", "Copiar seleção")) }
            TextButton(onClick = { session.paste() }, enabled = inputEnabled) { Text(t("Paste", "Colar")) }
            TextButton(onClick = { session.input("scrollUp", .5f, .5f) }, enabled = inputEnabled) { Text(t("Scroll up", "Subir")) }
            TextButton(onClick = { session.input("scrollDown", .5f, .5f) }, enabled = inputEnabled) { Text(t("Scroll down", "Descer")) }
            TextButton(onClick = { session.input("back") }, enabled = inputEnabled) { Text(t("Back / Esc", "Voltar / Esc")) }
            TextButton(onClick = { session.input("home") }, enabled = inputEnabled) { Text(t("Home", "Início")) }
        }
        if (inputOpen) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                OutlinedTextField(typed, { typed = it.take(512).let { value -> if (value.lastOrNull()?.isHighSurrogate() == true) value.dropLast(1) else value } }, Modifier.fillMaxWidth().focusRequester(focusRequester), label = { Text(t("Text to type", "Texto a escrever")) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), maxLines = 3)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { session.typeText(typed); typed = ""; focusRequester.requestFocus() }, enabled = typed.isNotEmpty() && inputEnabled) { Text(t("Send", "Enviar")) }
                TextButton(onClick = { session.input("enter") }, enabled = inputEnabled) { Text(t("Enter", "Enter")) }
                TextButton(onClick = { session.key("Backspace") }, enabled = inputEnabled) { Text(t("Delete", "Apagar")) }
                TextButton(onClick = { session.key("Tab") }, enabled = inputEnabled) { Text(t("Tab", "Tab")) }
                }
            }
        }
        }
        }
        }
        // Always discoverable, including before Controls is opened and while typing.
        FilledTonalButton(onClick = { onFullScreenChange(!fullScreen) }, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp).heightIn(min = 48.dp).focusRequester(exitFocus)) {
            Text(if (fullScreen) t("Exit full screen", "Sair do ecrã inteiro") else t("Full screen", "Ecrã inteiro"))
        }
        Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(top = 64.dp, start = 8.dp, end = 8.dp).widthIn(max = 360.dp)) {
            AnimatedVisibility(keyboardFocused, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration * 3 / 4))) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = ::releaseKeyboard) { Text(t("Keyboard on · Release", "Teclado ligado · Libertar")) }
                        if (!session.platform.isAndroid) Text("Ctrl + Shift + Space", Modifier.padding(start = 8.dp, bottom = 8.dp), fontSize = 12.sp)
                        if (composition.isNotEmpty()) Text(composition, Modifier.padding(8.dp))
                    }
                }
            }
            if (state.inputNotice.isNotEmpty()) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Text(when (state.inputNotice) {
                    "paste" -> t("Paste failed. Please try again.", "Não foi possível colar. Tente novamente.")
                    "busy" -> t("Typing paused. Please try again.", "Escrita em pausa. Tente novamente.")
                    else -> t("This field or key is unavailable. Try the device's keyboard.", "Este campo ou tecla não está disponível. Use o teclado do dispositivo.")
                }, Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp)
            }
        }
    }
}
