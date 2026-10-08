package app.betterdesk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.betterdesk.core.*
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun BetterDeskTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF9ED6BB), onPrimary = Color(0xFF103A2A), background = Color(0xFF151B18),
        surface = Color(0xFF1D2520), onSurface = Color(0xFFE4EBE5), onBackground = Color(0xFFE4EBE5),
        surfaceVariant = Color(0xFF2D3931), onSurfaceVariant = Color(0xFFBAC9BF)
    ) else lightColorScheme(
        primary = Color(0xFF22634E), onPrimary = Color(0xFFF5FBF7), background = Color(0xFFF5F7F3),
        surface = Color(0xFFFAFCF9), onSurface = Color(0xFF202C26), onBackground = Color(0xFF202C26),
        surfaceVariant = Color(0xFFE8EEE7), onSurfaceVariant = Color(0xFF516359)
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)), content = content)
}

@Composable
fun BetterDeskApp(session: HelpSession) {
    val state by session.state.collectAsState()
    var portuguese by remember { mutableStateOf(Locale.getDefault().language == "pt") }
    fun t(en: String, pt: String) = if (portuguese) pt else en
    val clipboard = LocalClipboardManager.current
    var invitationText by remember { mutableStateOf("") }
    var enterLink by remember { mutableStateOf(false) }
    var helperSetup by remember { mutableStateOf(false) }
    var helperName by remember { mutableStateOf(session.platform.defaultName) }
    var copied by remember(state.stage) { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(300L) }
    LaunchedEffect(state.stage) {
        while (state.stage == Stage.WAITING) {
            remaining = ((state.invitation!!.expires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text("B", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                }
                Text("BetterDesk", Modifier.padding(start = 10.dp).weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                TextButton(onClick = { portuguese = !portuguese }) { Text(if (portuguese) "English" else "Português") }
            }
            if (state.stage == Stage.VIEWING) {
                RemoteViewer(session, state, portuguese, Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                        when (state.stage) {
                            Stage.HOME -> if (helperSetup) {
                                Heading(t("Help someone", "Ajudar alguém"), t("Create an invitation and send it to them.", "Crie um convite e envie-o à pessoa."))
                                OutlinedTextField(helperName, { helperName = it.take(40) }, Modifier.fillMaxWidth(), label = { Text(t("Your name", "O seu nome")) }, singleLine = true)
                                Spacer(Modifier.height(20.dp))
                                Primary(t("Create invitation", "Criar convite"), helperName.isNotBlank()) { session.createInvitation(helperName) }
                                TextButton(onClick = { helperSetup = false }) { Text(t("Back", "Voltar")) }
                            } else {
                                Heading(t("Let's get you help.", "Vamos ajudar."), t("Open the invitation sent by the person helping you.", "Abra o convite que a pessoa que o vai ajudar lhe enviou."))
                                Primary(t("Paste invitation", "Colar convite")) {
                                    val value = clipboard.getText()?.text.orEmpty().trim()
                                    if (value.startsWith("betterdesk://")) session.acceptInvitation(value)
                                    else { enterLink = true; invitationText = "" }
                                }
                                if (enterLink) {
                                    Spacer(Modifier.height(16.dp))
                                    OutlinedTextField(invitationText, { invitationText = it.take(2048) }, Modifier.fillMaxWidth(), label = { Text(t("Invitation link", "Ligação do convite")) }, maxLines = 3)
                                    TextButton(onClick = { session.acceptInvitation(invitationText) }, enabled = invitationText.isNotBlank()) { Text(t("Open invitation", "Abrir convite")) }
                                }
                                Spacer(Modifier.height(32.dp))
                                TextButton(onClick = { helperSetup = true }) { Text(t("I'm here to help someone", "Quero ajudar alguém")) }
                            }
                            Stage.INVITATION -> {
                                Heading(t("${state.peer} is ready to help.", "${state.peer} vai ajudar."), t("Allow this person to see your screen and control your device. Only continue if you know who sent this invitation.", "Permita que esta pessoa veja o ecrã e controle o dispositivo. Continue apenas se conhece quem enviou o convite."))
                                Primary(t("Allow help", "Permitir ajuda")) { session.allowHelp() }
                                TextButton(onClick = { session.home() }) { Text(t("Decline", "Recusar")) }
                            }
                            Stage.PREPARING -> {
                                Heading(t("Preparing your invitation…", "A preparar o convite…"), t("This takes a moment.", "Só um momento."))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                TextButton(onClick = { session.home() }) { Text(t("Cancel", "Cancelar")) }
                            }
                            Stage.WAITING -> {
                                Heading(t("Your invitation is ready.", "O convite está pronto."), t("Send it to the person who needs help. Keep this window open.", "Envie-o à pessoa que precisa de ajuda. Mantenha esta janela aberta."))
                                Primary(if (copied) t("Copy invitation again", "Copiar convite novamente") else t("Copy invitation", "Copiar convite")) {
                                    clipboard.setText(AnnotatedString(state.invitation!!.link())); copied = true
                                }
                                Spacer(Modifier.height(16.dp))
                                Text(if (copied) t("Copied. Paste it into your conversation.", "Copiado. Cole-o na conversa com a pessoa.") else t("They will approve before sharing starts.", "A pessoa vai autorizar antes de partilhar o ecrã."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(12.dp))
                                Text(t("Expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", "Expira em ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"), fontSize = 14.sp)
                                TextButton(onClick = { session.home() }) { Text(t("Cancel invitation", "Cancelar convite")) }
                            }
                            Stage.CONNECTING -> {
                                Heading(t("Getting connected…", "A estabelecer ligação…"), t("Follow any screen-sharing prompt on the device receiving help.", "No dispositivo que recebe ajuda, confirme o pedido de partilha do ecrã."))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                TextButton(onClick = { session.stop() }) { Text(t("Cancel", "Cancelar")) }
                            }
                            Stage.CONTROL_PERMISSION -> {
                                Heading(t("Let them tap and type.", "Permita tocar e escrever."), t("In Android Accessibility settings, select BetterDesk and turn it on. It lets your approved helper interact with your screen during this session. Come back here when ready.", "Nas definições de Acessibilidade do Android, escolha BetterDesk e ative a opção. Permite à pessoa autorizada interagir com o ecrã durante esta ajuda. Depois volte aqui."))
                                Primary(t("Open accessibility settings", "Abrir acessibilidade")) { session.enableControl() }
                                TextButton(onClick = { session.shareWithoutControl() }) { Text(t("Share my screen only", "Partilhar apenas o ecrã")) }
                                TextButton(onClick = { session.home() }) { Text(t("Cancel", "Cancelar")) }
                            }
                            Stage.SHARING -> {
                                Heading(t("${state.peer} is helping you.", "${state.peer} está a ajudar."), if (state.remoteControl) t("Your screen is shared. They can tap and type. Stay with your device.", "O ecrã está partilhado. A pessoa pode tocar e escrever. Fique junto do dispositivo.") else t("Your screen is shared. Only you can tap and type.", "O ecrã está partilhado. Só o utilizador pode tocar e escrever."))
                                Primary(t("Stop help", "Terminar ajuda")) { session.stop() }
                                Spacer(Modifier.height(16.dp))
                                Text(t("You can continue using your device.", "Pode continuar a utilizar o dispositivo."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Stage.ENDED -> {
                                Heading(t("Help ended.", "A ajuda terminou."), t("Your screen is no longer shared. This connection is closed.", "O ecrã já não está partilhado. A ligação foi encerrada."))
                                Primary(t("Done", "Concluir")) { helperSetup = false; enterLink = false; session.home() }
                            }
                            Stage.ERROR -> {
                                Heading(if (state.error == "invitation") t("Let's try a new invitation.", "Vamos usar um novo convite.") else t("We couldn't stay connected.", "Não foi possível manter a ligação."), if (state.error == "invitation") t("This invitation is invalid or has expired. Ask for a new one.", "Este convite não é válido ou expirou. Peça um novo convite.") else t("Sharing has stopped. Make sure both devices are on the same network, then create a new invitation.", "A partilha foi interrompida. Confirme que os dispositivos estão na mesma rede e crie um novo convite."))
                                Primary(t("Start again", "Tentar novamente")) { helperSetup = false; enterLink = false; session.home() }
                            }
                            Stage.VIEWING -> Unit
                        }
                    }
                }
                Text(t("Early version · Both devices must be on the same network", "Versão inicial · Os dispositivos devem estar na mesma rede"), Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
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
@Composable private fun RemoteViewer(session: HelpSession, state: HelpState, pt: Boolean, modifier: Modifier) {
    fun t(en: String, portuguese: String) = if (pt) portuguese else en
    var typed by remember { mutableStateOf("") }
    var inputOpen by remember { mutableStateOf(false) }
    var fresh by remember { mutableStateOf(true) }
    LaunchedEffect(state.frameAt) {
        fresh = true; delay(2000); fresh = false
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (!fresh) t("Waiting for the screen…", "A aguardar o ecrã…") else if (state.remoteControl) t("You can control this screen", "Pode controlar este ecrã") else t("Viewing only", "Apenas visualização"), Modifier.weight(1f), fontSize = 14.sp)
            TextButton(onClick = { session.stop() }) { Text(t("End help", "Terminar ajuda")) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111814)).padding(8.dp), contentAlignment = Alignment.Center) {
            state.image?.let { image ->
                val ratio = image.width.toFloat() / image.height
                val width = minOf(maxWidth, maxHeight * ratio)
                val height = width / ratio
                Image(image, t("Shared screen", "Ecrã partilhado"), Modifier.size(width, height)
                    .pointerInput(session) {
                        detectTapGestures(onTap = { session.input("tap", (it.x / size.width).coerceIn(0f, 1f), (it.y / size.height).coerceIn(0f, 1f)) }, onLongPress = { session.input("right", (it.x / size.width).coerceIn(0f, 1f), (it.y / size.height).coerceIn(0f, 1f)) })
                    }.pointerInput(session) {
                        var start = Offset.Zero; var end = Offset.Zero
                        detectDragGestures(onDragStart = { start = it; end = it }, onDragEnd = {
                            session.input("swipe", (start.x / size.width).coerceIn(0f, 1f), (start.y / size.height).coerceIn(0f, 1f), (end.x / size.width).coerceIn(0f, 1f), (end.y / size.height).coerceIn(0f, 1f))
                        }) { change, _ -> change.consume(); end = change.position }
                    })
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { inputOpen = !inputOpen }, enabled = state.remoteControl && fresh) { Text(t("Type", "Escrever")) }
            TextButton(onClick = { session.input("scrollUp", .5f, .5f) }, enabled = state.remoteControl && fresh) { Text(t("Scroll up", "Subir")) }
            TextButton(onClick = { session.input("scrollDown", .5f, .5f) }, enabled = state.remoteControl && fresh) { Text(t("Scroll down", "Descer")) }
            TextButton(onClick = { session.input("back") }, enabled = state.remoteControl && fresh) { Text(t("Back / Esc", "Voltar / Esc")) }
            TextButton(onClick = { session.input("home") }, enabled = state.remoteControl && fresh) { Text(t("Home", "Início")) }
        }
        if (inputOpen) {
            Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(typed, { typed = it.take(512) }, Modifier.weight(1f), label = { Text(t("Text to type", "Texto a escrever")) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), maxLines = 3)
                TextButton(onClick = { session.input("text", text = typed); typed = "" }, enabled = typed.isNotEmpty() && fresh) { Text(t("Send", "Enviar")) }
                TextButton(onClick = { session.input("enter") }, enabled = fresh) { Text("↵") }
            }
        }
    }
}
