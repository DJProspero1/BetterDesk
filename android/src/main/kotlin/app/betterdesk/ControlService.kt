package app.betterdesk

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import app.betterdesk.core.*

class ControlService : AccessibilityService() {
    companion object { @Volatile var instance: ControlService? = null }
    override fun onServiceConnected() {
        instance = this
        // Uses the existing accessibility grant; does not replace the user's keyboard.
        if (Build.VERSION.SDK_INT >= 33) serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
        }
        // Turning control on mid-session upgrades the live session; the person stays in Settings.
        Runtime.session?.refreshPermissions()
    }
    override fun onDestroy() { instance = null; Runtime.session?.refreshPermissions(); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun apply(input: Message): Boolean {
        if (!CaptureBridge.active || !input.isValidInput() || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        val root = rootInActiveWindow ?: return false
        if (!root.refresh()) return false
        val owner = root.packageName?.toString().orEmpty()
        if (!AndroidInputPolicy.allows(owner, packageName, input.action)) return false
        if (input.action == "back") return performGlobalAction(GLOBAL_ACTION_BACK)
        if (input.action == "home") return performGlobalAction(GLOBAL_ACTION_HOME)
        if (input.action in setOf("tap", "right", "swipe", "scrollUp", "scrollDown")) {
            val manager = getSystemService(android.view.WindowManager::class.java)
            val metrics = android.util.DisplayMetrics()
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = manager.maximumWindowMetrics.bounds
                metrics.widthPixels = bounds.width(); metrics.heightPixels = bounds.height()
            } else { @Suppress("DEPRECATION") manager.defaultDisplay.getRealMetrics(metrics) }
            val x = input.x * (metrics.widthPixels - 1); val y = input.y * (metrics.heightPixels - 1)
            val path = Path().apply {
                when (input.action) {
                    "swipe" -> { moveTo(x, y); lineTo(input.endX * (metrics.widthPixels - 1), input.endY * (metrics.heightPixels - 1)) }
                    "scrollUp", "scrollDown" -> {
                        val up = input.action == "scrollUp"
                        moveTo(metrics.widthPixels * .5f, metrics.heightPixels * (if (up) .35f else .7f))
                        lineTo(metrics.widthPixels * .5f, metrics.heightPixels * (if (up) .7f else .35f))
                    }
                    else -> moveTo(x, y)
                }
            }
            val duration = when (input.action) { "tap" -> 70L; "right" -> 650L; else -> 300L }
            return CaptureBridge.active && dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
        }
        root.refresh()
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null && (!node.refresh() || node.packageName?.toString() != owner)) return false
        if (Build.VERSION.SDK_INT >= 33 && nativeEditor(input, owner, node)) return true
        return fallbackEditor(input, root, node)
    }

    @android.annotation.TargetApi(33)
    private fun nativeEditor(input: Message, owner: String, node: AccessibilityNodeInfo?): Boolean {
        val method = inputMethod ?: return false
        val info = method.currentInputEditorInfo ?: return false
        // Reject old/background editors, including our consent and OS settings screens.
        if (!method.currentInputStarted || info.packageName != owner || !AndroidInputPolicy.allows(info.packageName.orEmpty(), packageName, input.action)) return false
        val connection = method.currentInputConnection ?: return false
        val password = node?.isPassword == true || (info.inputType and 0xfff) in setOf(0x81, 0x91, 0xe1, 0x12)
        when (input.action) {
            "text" -> connection.commitText(input.text, 1, null)
            "copy", "cut", "paste", "selectAll" -> {
                if (password && input.action in setOf("copy", "cut")) return false
                connection.performContextMenuAction(when (input.action) {
                    "copy" -> android.R.id.copy; "cut" -> android.R.id.cut; "paste" -> android.R.id.paste; else -> android.R.id.selectAll
                })
            }
            "key", "enter", "backspace" -> {
                val name = when (input.action) { "enter" -> "Enter"; "backspace" -> "Backspace"; else -> input.key }
                if (name == "Enter" && input.modifiers == 0 && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
                    val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
                    if (info.actionId != 0 || action !in setOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED)) {
                        connection.performEditorAction(if (info.actionId != 0) info.actionId else action)
                        return true
                    }
                }
                val code = androidKey(name) ?: return false
                val meta = (if (input.modifiers and RemoteKeys.SHIFT != 0) KeyEvent.META_SHIFT_ON else 0) or
                    (if (input.modifiers and RemoteKeys.CTRL != 0) KeyEvent.META_CTRL_ON else 0) or
                    (if (input.modifiers and RemoteKeys.ALT != 0) KeyEvent.META_ALT_ON else 0)
                val time = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    connection.sendKeyEvent(KeyEvent(time, time, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
                }
            }
            else -> return false
        }
        // The API is asynchronous: submission is not an acknowledgement of execution.
        return true
    }

    private fun fallbackEditor(input: Message, root: AccessibilityNodeInfo, focused: AccessibilityNodeInfo?): Boolean {
        if (input.action == "key" && input.key == "Tab" && input.modifiers and (RemoteKeys.CTRL or RemoteKeys.ALT) == 0) {
            val direction = if (input.modifiers and RemoteKeys.SHIFT != 0) View.FOCUS_BACKWARD else View.FOCUS_FORWARD
            return (focused ?: root).focusSearch(direction)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true
        }
        val node = focused ?: return false
        if (node.isPassword) return false // Never rewrite masked text using ACTION_SET_TEXT.
        when (input.action) {
            "copy" -> return node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            "cut" -> return node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            "paste" -> return node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            "selectAll" -> return selection(node, 0, node.text?.length ?: 0)
        }
        if (!node.isEditable) return false
        var edit = input
        if (input.action == "enter" || (input.action == "key" && input.key == "Enter" && input.modifiers == 0)) {
            if (Build.VERSION.SDK_INT >= 30 && node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
            if (!node.isMultiLine) return false
            edit = input.copy(action = "text", text = "\n", key = "")
        }
        val text = node.text?.toString().orEmpty()
        val start = if (text.isEmpty()) 0 else node.textSelectionStart
        val end = if (text.isEmpty()) 0 else node.textSelectionEnd
        val result = TextEditing.apply(text, start, end, edit) ?: return false
        if (result.text != text && !node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result.text)
            })) return false
        return selection(node, result.start, result.end)
    }

    private fun selection(node: AccessibilityNodeInfo, start: Int, end: Int) = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
    })

    private fun androidKey(key: String): Int? = when {
        key.length == 1 && key[0] in 'A'..'Z' -> KeyEvent.KEYCODE_A + (key[0] - 'A')
        key.length == 1 && key[0] in '0'..'9' -> KeyEvent.KEYCODE_0 + (key[0] - '0')
        key.matches(Regex("F([1-9]|1[0-2])")) -> KeyEvent.KEYCODE_F1 + key.drop(1).toInt() - 1
        else -> mapOf("Enter" to KeyEvent.KEYCODE_ENTER, "Tab" to KeyEvent.KEYCODE_TAB, "Backspace" to KeyEvent.KEYCODE_DEL,
            "Delete" to KeyEvent.KEYCODE_FORWARD_DEL, "Escape" to KeyEvent.KEYCODE_ESCAPE, "Left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "Right" to KeyEvent.KEYCODE_DPAD_RIGHT, "Up" to KeyEvent.KEYCODE_DPAD_UP, "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END, "PageUp" to KeyEvent.KEYCODE_PAGE_UP,
            "PageDown" to KeyEvent.KEYCODE_PAGE_DOWN, "Insert" to KeyEvent.KEYCODE_INSERT, "Space" to KeyEvent.KEYCODE_SPACE)[key]
    }
}
