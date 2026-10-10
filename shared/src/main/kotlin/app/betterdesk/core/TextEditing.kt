package app.betterdesk.core

/** Android 8-12 fallback. UTF-16 offsets; movement and deletion respect code points. */
data class EditedText(val text: String, val start: Int, val end: Int)

object TextEditing {
    fun apply(text: String, anchor: Int, cursor: Int, input: Message): EditedText? {
        if (text.length > 262144 || anchor !in 0..text.length || cursor !in 0..text.length) return null
        if (listOf(anchor, cursor).any { it in 1 until text.length && text[it].isLowSurrogate() && text[it - 1].isHighSurrogate() }) return null
        val low = minOf(anchor, cursor); val high = maxOf(anchor, cursor)
        fun previous(at: Int) = if (at > 0) text.offsetByCodePoints(at, -1) else 0
        fun next(at: Int) = if (at < text.length) text.offsetByCodePoints(at, 1) else text.length
        fun word(at: Int, backwards: Boolean): Int {
            var p = at
            if (backwards) {
                while (p > 0 && Character.isWhitespace(text.codePointBefore(p))) p = previous(p)
                while (p > 0 && !Character.isWhitespace(text.codePointBefore(p))) p = previous(p)
            } else {
                while (p < text.length && !Character.isWhitespace(text.codePointAt(p))) p = next(p)
                while (p < text.length && Character.isWhitespace(text.codePointAt(p))) p = next(p)
            }
            return p
        }
        fun lineStart(at: Int) = if (at == 0) 0 else text.lastIndexOf('\n', at - 1) + 1
        fun lineEnd(at: Int) = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        fun replace(from: Int, to: Int, replacement: String): EditedText {
            val result = text.replaceRange(from, to, replacement)
            val caret = from + replacement.length
            return EditedText(result, caret, caret)
        }
        if (input.action == "text") return replace(low, high, input.text)
        val key = if (input.action == "backspace") "Backspace" else input.key
        val ctrl = input.modifiers and RemoteKeys.CTRL != 0
        val shift = input.modifiers and RemoteKeys.SHIFT != 0
        if (input.modifiers and RemoteKeys.ALT != 0) return null
        if (key == "Backspace" || key == "Delete") {
            if (low != high) return replace(low, high, "")
            return if (key == "Backspace") replace(if (ctrl) word(low, true) else previous(low), high, "")
            else replace(low, if (ctrl) word(high, false) else next(high), "")
        }
        val target = when (key) {
            "Left" -> if (!shift && low != high) low else if (ctrl) word(cursor, true) else previous(cursor)
            "Right" -> if (!shift && low != high) high else if (ctrl) word(cursor, false) else next(cursor)
            "Home" -> if (ctrl) 0 else lineStart(cursor)
            "End" -> if (ctrl) text.length else lineEnd(cursor)
            "Up", "Down" -> {
                if (ctrl) return null
                val start = lineStart(cursor)
                val column = text.codePointCount(start, cursor)
                val otherStart = if (key == "Up") { if (start == 0) 0 else lineStart(start - 1) }
                    else { val end = lineEnd(cursor); if (end == text.length) end else end + 1 }
                val otherEnd = lineEnd(otherStart)
                text.offsetByCodePoints(otherStart, minOf(column, text.codePointCount(otherStart, otherEnd)))
            }
            else -> return null
        }
        return EditedText(text, if (shift) anchor else target, target)
    }
}
