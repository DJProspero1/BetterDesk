package app.betterdesk

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.betterdesk.core.Message
import app.betterdesk.core.isValidInput
import app.betterdesk.core.AndroidInputPolicy

class ControlService : AccessibilityService() {
    companion object { @Volatile var instance: ControlService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; Runtime.session?.refreshPermissions(); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun apply(input: Message) {
        if (!CaptureBridge.active || !input.isValidInput() || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val root = rootInActiveWindow ?: return
        val owner = root.packageName?.toString().orEmpty()
        // Home/Back may leave BetterDesk; taps/text still cannot approve a request.
        if (!AndroidInputPolicy.allows(owner, packageName, input.action)) return
        val metrics = android.util.DisplayMetrics()
        val manager = getSystemService(android.view.WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = manager.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width(); metrics.heightPixels = bounds.height()
        } else { @Suppress("DEPRECATION") manager.defaultDisplay.getRealMetrics(metrics) }
        val x = input.x * (metrics.widthPixels - 1); val y = input.y * (metrics.heightPixels - 1)
        fun gesture(path: Path, duration: Long = 70) {
            if (CaptureBridge.active) dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
        }
        when (input.action) {
            "tap" -> gesture(Path().apply { moveTo(x, y) })
            "right" -> gesture(Path().apply { moveTo(x, y) }, 650)
            "swipe" -> gesture(Path().apply { moveTo(x, y); lineTo(input.endX * (metrics.widthPixels - 1), input.endY * (metrics.heightPixels - 1)) }, 300)
            "scrollUp" -> gesture(Path().apply { moveTo(metrics.widthPixels * .5f, metrics.heightPixels * .35f); lineTo(metrics.widthPixels * .5f, metrics.heightPixels * .7f) }, 300)
            "scrollDown" -> gesture(Path().apply { moveTo(metrics.widthPixels * .5f, metrics.heightPixels * .7f); lineTo(metrics.widthPixels * .5f, metrics.heightPixels * .35f) }, 300)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "copy", "paste", "selectAll" -> {
                val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
                if (node.isPassword) return
                when (input.action) {
                    "copy" -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
                    "paste" -> if (node.isEditable) node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    "selectAll" -> node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
                    })
                }
            }
            "text", "backspace" -> {
                val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
                if (!node.isEditable) return
                val existing = node.text?.toString().orEmpty()
                val start = node.textSelectionStart.coerceIn(0, existing.length)
                val end = node.textSelectionEnd.coerceIn(start, existing.length)
                val result = if (input.action == "text") existing.replaceRange(start, end, input.text)
                else existing.removeRange(if (start == end) (start - 1).coerceAtLeast(0) else start, end)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result) })
            }
            "enter" -> if (Build.VERSION.SDK_INT >= 30) root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }
}
