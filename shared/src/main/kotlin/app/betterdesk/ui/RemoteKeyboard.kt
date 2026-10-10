package app.betterdesk.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import app.betterdesk.core.HelpSession
import app.betterdesk.core.RemoteKeys

/** Native text input preserves layouts, dead keys and IME composition on both targets.
 * Only this explicitly focused receiver forwards keys; local app fields never do.
 */
@Composable
internal fun RemoteKeyboard(session: HelpSession, enabled: Boolean, focus: FocusRequester,
    modifier: Modifier, onFocus: (Boolean) -> Unit, onComposition: (String) -> Unit, release: () -> Unit) {
    val value = remember(session) { TextFieldState() }
    var focused by remember { mutableStateOf(false) }
    val handled = remember { mutableSetOf<Key>() }
    fun clear() { value.edit { replace(0, length, "") }; onComposition("") }
    fun flush() {
        if (enabled && focused && value.composition == null && value.text.isNotEmpty()) {
            session.typeText(value.text.toString()); clear()
        }
    }
    LaunchedEffect(value, enabled, focused) {
        snapshotFlow { value.text.toString() to value.composition }.collect {
            if (!enabled || !focused) clear()
            else if (value.composition != null) onComposition(value.text.take(64).toString())
            else flush()
        }
    }
    BasicTextField(state = value, inputTransformation = InputTransformation.maxLength(8192),
        modifier = modifier.drawWithContent { /* The remote image is the editor. */ }.focusRequester(focus).onFocusChanged {
        if (focused && !it.isFocused) {
            session.releaseKeyboard(); clear(); handled.clear()
        }
        focused = it.isFocused; onFocus(it.isFocused)
    }.onPreviewKeyEvent { event ->
        if (!enabled) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent handled.remove(event.key)
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (event.key == Key.Spacebar && event.isCtrlPressed && event.isShiftPressed && !event.isAltPressed) {
            clear(); release(); return@onPreviewKeyEvent true
        }
        // Let the local IME finish/cancel a composition before forwarding editing keys.
        if (value.composition != null) return@onPreviewKeyEvent false
        flush()
        val consumed = forwardKey(session, event)
        if (consumed) handled.add(event.key)
        consumed
    }, enabled = enabled, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, showKeyboardOnFocus = false))
}

private fun forwardKey(session: HelpSession, event: KeyEvent): Boolean {
    if (event.isMetaPressed) return false // Reserved for the local OS.
    val ctrl = event.isCtrlPressed; val alt = event.isAltPressed; val shift = event.isShiftPressed
    val modifiers = (if (shift) RemoteKeys.SHIFT else 0) or (if (ctrl) RemoteKeys.CTRL else 0) or (if (alt) RemoteKeys.ALT else 0)
    // Ctrl+Alt character chords can be AltGr. Let the native input system resolve them.
    if (ctrl && !alt) {
        when (event.key) {
            Key.V -> { session.paste(if (shift) RemoteKeys.SHIFT else 0); return true }
            Key.C -> if (!shift) { session.input("copy"); return true }
            Key.X -> if (!shift) { session.input("cut"); return true }
            Key.A -> if (!shift) { session.input("selectAll"); return true }
            Key.Insert -> if (!shift) { session.input("copy"); return true }
        }
    }
    if (shift && !ctrl && !alt) {
        if (event.key == Key.Insert) { session.paste(); return true }
        if (event.key == Key.Delete) { session.input("cut"); return true }
    }
    if (event.key == Key.Escape && modifiers == 0) { session.input("back"); return true }
    val key = navigationKeys[event.key] ?: if ((ctrl || alt) && !(ctrl && alt)) characterKeys[event.key] else null
    if (key == null) return false
    session.key(key, modifiers)
    return true
}

private val navigationKeys = mapOf(
    Key.Enter to "Enter", Key.NumPadEnter to "Enter", Key.Tab to "Tab", Key.Backspace to "Backspace",
    Key.Delete to "Delete", Key.Escape to "Escape", Key.DirectionLeft to "Left", Key.DirectionRight to "Right",
    Key.DirectionUp to "Up", Key.DirectionDown to "Down", Key.MoveHome to "Home", Key.MoveEnd to "End",
    Key.PageUp to "PageUp", Key.PageDown to "PageDown", Key.Insert to "Insert",
    Key.F1 to "F1", Key.F2 to "F2", Key.F3 to "F3", Key.F4 to "F4", Key.F5 to "F5", Key.F6 to "F6",
    Key.F7 to "F7", Key.F8 to "F8", Key.F9 to "F9", Key.F10 to "F10", Key.F11 to "F11", Key.F12 to "F12")
private val characterKeys = listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K,
    Key.L, Key.M, Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z)
    .zip(('A'..'Z').map { it.toString() }).toMap() +
    listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        .zip(('0'..'9').map { it.toString() }).toMap() + (Key.Spacebar to "Space")
