package app.betterdesk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
        override var languageTag: String? = null
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
    @Test fun `language flag writes preference and restores it in a new app composition`() {
        val platform = Platform().apply { languageTag = "en" }
        val session = HelpSession(platform, bindAddress = java.net.InetAddress.getLoopbackAddress(), relayFactory = { null })
        var instance by mutableStateOf(0)
        try {
            compose.setContent { key(instance) { BetterDeskTheme { BetterDeskApp(session) } } }
            compose.onNodeWithText("Connect").assertIsDisplayed()
            compose.onNodeWithTag("language").performClick()
            compose.onNodeWithText("Ligar").assertIsDisplayed()
            kotlin.test.assertEquals("pt", platform.languageTag)
            compose.runOnIdle { instance++ }
            compose.onNodeWithText("Ligar").assertIsDisplayed()
            compose.onNodeWithTag("language").assertContentDescriptionContains("Português. Change language to English")
        } finally { session.dispose() }
    }

    @Test fun `viewer fits the whole viewport through rotation and controls never shrink it`() {
        val session = HelpSession(Platform(), relayFactory = { null })
        val fixture = java.awt.image.BufferedImage(1600,900,java.awt.image.BufferedImage.TYPE_INT_RGB)
        fixture.createGraphics().apply {
            color=java.awt.Color(220,220,220);fillRect(0,0,1600,900)
            color=java.awt.Color(50,50,50);fillRect(0,0,1600,60)
            font=java.awt.Font("SansSerif",java.awt.Font.PLAIN,54);drawString("Remote screen test fixture",100,180)
            color=java.awt.Color(150,150,150);fillRect(100,240,1400,560);dispose()
        }
        val encoded=java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(fixture,"png",encoded)
        val image=org.jetbrains.skia.Image.makeFromEncoded(encoded.toByteArray()).toComposeImageBitmap()
        var landscape by mutableStateOf(false)
        var fullscreen by mutableStateOf(true)
        try {
            compose.setContent {
                Box(Modifier.requiredSize(if(landscape) 800.dp else 390.dp, if(landscape) 390.dp else 800.dp).testTag("screen")) {
                    BetterDeskTheme { RemoteViewer(session, HelpState(Stage.VIEWING,image=image,remoteControl=true), false, Modifier.fillMaxSize(),fullscreen) {fullscreen=it} }
                }
            }
            compose.onNodeWithTag("remote-image").assertWidthIsEqualTo(390.dp)
            compose.onNodeWithText("BetterDesk").assertDoesNotExist()
            compose.onNodeWithText("Disconnect").assertDoesNotExist()
            snapshot("viewer-portrait")
            compose.runOnIdle { landscape = true }
            compose.onNodeWithTag("remote-image").assertHeightIsEqualTo(390.dp)
            val before=compose.onNodeWithTag("remote-image").fetchSemanticsNode().boundsInRoot
            snapshot("viewer-landscape")
            compose.onNodeWithText("Controls").performClick()
            compose.onNodeWithText("Disconnect").assertIsDisplayed()
            kotlin.test.assertEquals(before,compose.onNodeWithTag("remote-image").fetchSemanticsNode().boundsInRoot)
            snapshot("viewer-controls")
            compose.onNodeWithText("Exit full screen").performClick()
            compose.onNodeWithText("Full screen").assertIsDisplayed()
            compose.onNodeWithText("Hide").performClick()
            compose.onNodeWithText("Controls").assertIsDisplayed()
        } finally { session.dispose() }
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
