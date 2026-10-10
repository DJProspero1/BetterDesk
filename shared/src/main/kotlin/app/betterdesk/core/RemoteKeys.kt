package app.betterdesk.core

/** Symbolic keys, independent of Android/AWT key codes. Chords are atomic, never held. */
object RemoteKeys {
    const val SHIFT = 1
    const val CTRL = 2
    const val ALT = 4
    val allowed = ('A'..'Z').map { it.toString() }.toSet() + ('0'..'9').map { it.toString() } +
        (1..12).map { "F$it" } + setOf("Enter", "Tab", "Backspace", "Delete", "Escape", "Left", "Right", "Up", "Down", "Home", "End", "PageUp", "PageDown", "Insert", "Space")
}

/** Reject malformed UTF-16 and control characters that are not text editing input. */
fun String.isWellFormedInputText(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i++]
        if (c.isHighSurrogate()) {
            if (i >= length || !this[i++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate() || (c.isISOControl() && c !in "\n\r\t")) return false
    }
    return true
}

/** Never split an emoji/supplementary character at the wire's UTF-16 limit. */
fun inputTextChunks(text: String): List<String> {
    if (!text.isWellFormedInputText() || text.length > 8192) return emptyList()
    val parts = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 512, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate()) end--
        parts += text.substring(start, end)
        start = end
    }
    return parts
}
