package app.betterdesk

import android.app.NotificationManager
import android.content.Intent
import android.content.res.Configuration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.betterdesk.core.Stage
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.regex.Pattern

/** Opt-in test APK only. Drives real Android consent; never grants permissions with app-ops. */
class RelayDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun until(timeout: Long = 30000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition()) { if(System.currentTimeMillis()>=end) throw AssertionError("Android relay state timed out: ${Runtime.session?.state?.value?.stage}; ${Runtime.session?.failureSummary}"); Thread.sleep(100) }
    }
    private fun click(text: String) {
        repeat(6) {
            val item = device.wait(Until.findObject(By.text(Pattern.compile(text))),1500)
            if(item!=null) { item.click(); return }
            val scroll = device.findObject(By.res("com.android.systemui","screen_share_permission_dialog"))
                ?: device.findObject(By.scrollable(true).pkg("app.betterdesk"))
            scroll?.scroll(Direction.DOWN,.7f)
        }
        error("Required control unavailable")
    }
    private fun approveProjection() {
        val dialog=By.res("com.android.systemui","screen_share_permission_dialog")
        repeat(8) {
            device.waitForIdle()
            val button=device.wait(Until.findObject(By.res("com.android.systemui","start_button")),1000)
                ?: device.findObject(By.text(Pattern.compile("Start now|Share screen|Start|Iniciar agora|Partilhar ecrã|Iniciar")))
            if(button!=null) {
                button.click()
                if(device.wait(Until.gone(dialog),1500)) return
            }
            device.findObject(dialog)?.scroll(Direction.DOWN,.7f)
            device.waitForIdle()
        }
        error("Genuine Android capture approval did not complete")
    }
    @Test fun relaySession() {
        assumeTrue(args.getString("relayTest") == "1")
        val id = args.getString("runId")!!.also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
        val mode = args.getString("mode")!!
        val codeFile = File(context.filesDir,"relay-code-$id")
        if(device.hasObject(By.res("com.android.systemui","screen_share_permission_dialog")))device.pressBack()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                if(mode=="share") {
                    until { Runtime.session?.state?.value?.deviceCode?.length == 12 }
                    codeFile.writeText(Runtime.session!!.state.value.deviceCode)
                    until(60000) { Runtime.session?.state?.value?.stage == Stage.INVITATION }
                    assertTrue(Runtime.session!!.state.value.viaRelay)
                    click("Allow connection|Permitir ligação")
                    until { Runtime.session!!.state.value.stage in setOf(Stage.REMEMBER,Stage.CONNECTING,Stage.SHARING) }
                    if (Runtime.session!!.state.value.stage == Stage.REMEMBER) click("Just this time|Só desta vez")
                    until { Runtime.session!!.state.value.stage in setOf(Stage.CONNECTING,Stage.SHARING) }
                    // Use the genuine OS prompts for playback audio and notifications.
                    repeat(3) {
                        val allow = device.wait(Until.findObject(By.res(Pattern.compile(".*permissioncontroller:id/permission_allow(_foreground_only)?_button"))),1500)
                        allow?.click()
                    }
                    approveProjection()
                    until { Runtime.session!!.state.value.stage==Stage.SHARING }
                    until { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id==1 } }
                    val sound=args.getString("videoOnly")!="1"
                    assertEquals(sound,Runtime.session!!.state.value.audioAvailable)
                    context.startActivity(Intent().setClassName("app.betterdesk.motionfixture","app.betterdesk.motionfixture.MotionActivity").putExtra("audio",sound).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    until(120000) { Runtime.session!!.state.value.stage in setOf(Stage.ENDED,Stage.ERROR) }
                    until { !CaptureBridge.active && context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty() }
                } else {
                    val code = args.getString("code")!!.also { require(it.matches(Regex("[0-9]{12}"))) }
                    val input = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg("app.betterdesk")),15000) ?: error("Code field unavailable")
                    input.text = code
                    click("Connect|Ligar")
                    File(context.filesDir,"relay-progress.txt").writeText("Connecting")
                    until(60000) {
                        val state=Runtime.session!!.state.value
                        check(state.stage !in setOf(Stage.ERROR,Stage.ENDED)) { "Android relay failed: ${state.stage}; ${Runtime.session!!.failureSummary}" }
                        state.stage==Stage.VIEWING
                    }
                    File(context.filesDir,"relay-progress.txt").writeText("Viewing")
                    assertTrue(Runtime.session!!.state.value.viaRelay)
                    assertTrue(Runtime.session!!.state.value.image!!.width > 100)
                    val connectedSession = Runtime.session
                    assertNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Controls|Controlos"))),10000))
                    fun barsHidden(): Boolean {
                        var hidden = false
                        scenario.onActivity { activity ->
                            val insets=ViewCompat.getRootWindowInsets(activity.window.decorView)
                            hidden=insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars()) && !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
                        }
                        return hidden
                    }
                    until { barsHidden() }
                    assertNull(device.findObject(By.text("BetterDesk")))
                    assertNotNull(device.findObject(By.text(Pattern.compile("Exit full screen|Sair do ecrã inteiro"))))
                    until { Runtime.platform!!.audioFramesPlayed() > 4800 }
                    device.takeScreenshot(File(context.filesDir,"relay-viewer-portrait.png"))
                    device.setOrientationLeft()
                    until { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    until { barsHidden() }
                    assertSame(connectedSession,Runtime.session)
                    val screen=device.wait(Until.findObject(By.desc(Pattern.compile("Shared screen|Ecrã partilhado"))),10000) ?: error("Remote image unavailable")
                    val bounds=screen.visibleBounds
                    val remote=Runtime.session!!.state.value.image!!
                    val expectedHeight=minOf(device.displayHeight.toFloat(),device.displayWidth.toFloat()*remote.height/remote.width)
                    assertTrue("Landscape image must fit the full display",kotlin.math.abs(bounds.height()-expectedHeight)<4)
                    device.takeScreenshot(File(context.filesDir,"relay-viewer-landscape.png"))
                    click("Controls|Controlos")
                    click("Exit full screen|Sair do ecrã inteiro")
                    until { !barsHidden() }
                    click("Full screen|Ecrã inteiro")
                    until { barsHidden() }
                    assertNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Exit full screen|Sair do ecrã inteiro"))),10000))
                    assertNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Type|Escrever"))),10000))
                    device.waitForIdle()
                    device.takeScreenshot(File(context.filesDir,"relay-viewer.png"))
                    // A controlled desktop fixture receives this input; no unrelated PC UI is touched.
                    click("Type|Escrever")
                    val text = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg("app.betterdesk")),5000) ?: error("Text field unavailable")
                    device.takeScreenshot(File(context.filesDir,"relay-viewer-keyboard.png"))
                    text.text = "BetterDesk relay test"
                    click("Send|Enviar")
                    click("Hide|Ocultar")
                    File(context.filesDir,"relay-progress.txt").writeText("Input sent")
                    // Either device can request the reversal; each new Android capture
                    // must use a fresh, genuine MediaProjection approval.
                    click("Switch screens|Trocar ecrãs")
                    until { Runtime.session!!.state.value.stage in setOf(Stage.CONNECTING,Stage.SHARING) }
                    approveProjection()
                    until { Runtime.session!!.state.value.stage==Stage.SHARING }
                    assertSame(connectedSession,Runtime.session)
                    until { CaptureBridge.active && context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id==1 } }
                    until { Runtime.session!!.state.value.switchState=="incoming" }
                    click("Allow switch|Permitir troca")
                    until { Runtime.session!!.state.value.stage==Stage.VIEWING }
                    until { !CaptureBridge.active && context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id==1 } }
                    until { barsHidden() }
                    assertNotNull(device.findObject(By.text(Pattern.compile("Exit full screen|Sair do ecrã inteiro"))))
                    click("Controls|Controlos")
                    click("Disconnect|Desligar")
                    until { Runtime.session!!.state.value.stage==Stage.ENDED }
                    until {
                        var visible = false
                        scenario.onActivity { activity -> visible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true }
                        visible
                    }
                }
            } catch(e: Throwable) {
                device.takeScreenshot(File(context.filesDir,"relay-failure.png"))
                device.dumpWindowHierarchy(File(context.filesDir,"relay-failure.xml"))
                throw e
            } finally {
                if(device.hasObject(By.res("com.android.systemui","screen_share_permission_dialog")))device.pressBack()
                if (mode == "view") { device.setOrientationNatural(); device.unfreezeRotation() }
                codeFile.delete()
                scenario.onActivity { Runtime.session?.stop() }
            }
        }
    }
}
