package app.betterdesk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Draw the flags consistently, including on Windows where flag emoji may be letters. */
@Composable internal fun LanguageButton(portuguese: Boolean, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp).testTag("language").semantics {
        contentDescription = if (portuguese) "Português. Change language to English" else "English. Mudar idioma para português"
    }) {
        Canvas(Modifier.size(28.dp, 18.dp)) {
            if (portuguese) {
                drawRect(Color(0xFFCE1126))
                drawRect(Color(0xFF006600), size = Size(size.width * .4f, size.height))
                val center = Offset(size.width * .4f, size.height * .5f)
                drawCircle(Color(0xFFF5CE3E), size.height * .25f, center)
                drawRect(Color.White, center - Offset(size.height * .10f, size.height * .17f), Size(size.height * .2f, size.height * .3f))
                drawRect(Color(0xFFCE1126), center - Offset(size.height * .10f, size.height * .17f), Size(size.height * .2f, size.height * .3f), style = Stroke(size.height * .04f))
            } else {
                drawRect(Color(0xFF012169))
                drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), size.height * .24f)
                drawLine(Color.White, Offset(0f, size.height), Offset(size.width, 0f), size.height * .24f)
                drawLine(Color(0xFFC8102E), Offset.Zero, Offset(size.width, size.height), size.height * .09f)
                drawLine(Color(0xFFC8102E), Offset(0f, size.height), Offset(size.width, 0f), size.height * .09f)
                drawRect(Color.White, Offset(size.width * .39f, 0f), Size(size.width * .22f, size.height))
                drawRect(Color.White, Offset(0f, size.height * .33f), Size(size.width, size.height * .34f))
                drawRect(Color(0xFFC8102E), Offset(size.width * .44f, 0f), Size(size.width * .12f, size.height))
                drawRect(Color(0xFFC8102E), Offset(0f, size.height * .4f), Size(size.width, size.height * .2f))
            }
        }
    }
}
