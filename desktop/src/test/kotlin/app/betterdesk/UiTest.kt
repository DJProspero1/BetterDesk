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
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

class UiTest {
    @get:Rule val compose = createComposeRule()
    private class Platform(override val isAndroid: Boolean = false) : RemotePlatform {
        override val mayRestrictSettings get() = isAndroid
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
        val fixture = System.getenv("BETTERDESK_BROKER_FIXTURE")?.let(::File)?.takeIf { it.isFile }?.let {
            ProcessBuilder("node", it.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        }
        val base = fixture?.inputStream?.bufferedReader()?.readLine()?.let {
            Json.parseToJsonElement(it).jsonObject.getValue("url").jsonPrimitive.content
        }
        val session = HelpSession(Platform(width < 500), relayFactory = { base?.let { RelayClient(it, true) } })
        try {
            compose.setContent {
                Box(Modifier.requiredSize(width.dp, height.dp).testTag("screen")) {
                    BetterDeskTheme { BetterDeskApp(session) }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Connect").assertIsDisplayed()
            compose.onNodeWithText("Device code").assertIsDisplayed()
            if(base != null) {
                compose.waitUntil(15000) { session.state.value.deviceCode.length == 12 }
                compose.onNodeWithText(session.state.value.deviceCode.chunked(4).joinToString(" ")).assertIsDisplayed()
            }
            snapshot(name + "-home")
            compose.runOnIdle { session.acceptInvitation(Invitation(listOf("127.0.0.1"), 33333, "ab".repeat(32), Invitation.secret(), "Luís", System.currentTimeMillis() + 60_000).link()) }
            compose.onNodeWithText("Allow connection").assertIsDisplayed()
            compose.onNodeWithText("Decline").assertIsDisplayed()
            snapshot(name + "-consent")
            if (width < 500) {
                compose.onNodeWithText("Allow connection").performClick()
                compose.onNodeWithText("Open control settings").assertIsDisplayed()
                snapshot(name + "-permissions")
                compose.onNodeWithText("Android blocked this?").performClick()
                compose.onNodeWithText("Open app info").assertIsDisplayed()
                snapshot(name + "-restricted-settings")
            } else compose.onNodeWithText("Decline").performClick()
        } finally {
            session.dispose(); Locale.setDefault(oldLocale)
            fixture?.let { it.outputStream.close(); if (!it.waitFor(3, TimeUnit.SECONDS)) it.destroyForcibly() }
        }
    }
    private fun snapshot(name: String) {
        val bitmap = compose.onNodeWithTag("screen").captureToImage()
        val data = Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name.png").writeBytes(data.bytes)
    }
}
