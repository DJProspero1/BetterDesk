package app.betterdesk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.betterdesk.core.*
import app.betterdesk.ui.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class UiTest {
    @get:Rule val compose = createComposeRule()
    private class Platform : RemotePlatform {
        override val isAndroid = false
        override val defaultName = "Luís"
        override fun controlAvailable() = false
        override fun openControlSettings() = Unit
        override suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit) = false
        override fun stopSharing() = Unit
        override fun applyInput(input: Message) = Unit
        override fun decode(frame: Packet.Frame): ImageBitmap? = null
    }

    @Test fun `mobile layout has same primary action and helper flow`() {
        renderAndInspect(390, 740, "phone")
    }
    @Test fun `desktop layout has same primary action and helper flow`() {
        renderAndInspect(760, 740, "desktop")
    }
    private fun renderAndInspect(width: Int, height: Int, name: String) {
        val oldLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        val session = HelpSession(Platform())
        try {
            compose.setContent {
                Box(Modifier.requiredSize(width.dp, height.dp).testTag("screen")) {
                    BetterDeskTheme { BetterDeskApp(session) }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Paste invitation").assertIsDisplayed()
            snapshot(name + "-home")
            compose.onNodeWithText("I'm here to help someone").performClick()
            compose.onNodeWithText("Create invitation").assertIsDisplayed()
            snapshot(name + "-helper")
            compose.runOnIdle { session.acceptInvitation(Invitation(listOf("127.0.0.1"), 33333, "ab".repeat(32), Invitation.secret(), "Luís", System.currentTimeMillis() + 60_000).link()) }
            compose.onNodeWithText("Allow help").assertIsDisplayed()
            compose.onNodeWithText("Decline").assertIsDisplayed()
            snapshot(name + "-consent")
            compose.onNodeWithText("Decline").performClick()
        } finally { session.dispose(); Locale.setDefault(oldLocale) }
    }
    private fun snapshot(name: String) {
        val bitmap = compose.onNodeWithTag("screen").captureToImage()
        val data = Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name.png").writeBytes(data.bytes)
    }
}
