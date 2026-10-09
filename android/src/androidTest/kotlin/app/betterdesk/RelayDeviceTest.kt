package app.betterdesk

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
        val item = device.wait(Until.findObject(By.text(Pattern.compile(text))),10000) ?: error("Required control unavailable")
        item.click()
    }
    @Test fun relaySession() {
        assumeTrue(args.getString("relayTest") == "1")
        val id = args.getString("runId")!!.also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
        val mode = args.getString("mode")!!
        val codeFile = File(context.filesDir,"relay-code-$id")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                if(mode=="share") {
                    until { Runtime.session?.state?.value?.deviceCode?.length == 12 }
                    codeFile.writeText(Runtime.session!!.state.value.deviceCode)
                    until(60000) { Runtime.session?.state?.value?.stage == Stage.INVITATION }
                    assertTrue(Runtime.session!!.state.value.viaRelay)
                    click("Allow connection|Permitir ligação")
                    until { Runtime.session!!.state.value.stage in setOf(Stage.CONTROL_PERMISSION,Stage.CONNECTING) }
                    if(Runtime.session!!.state.value.stage==Stage.CONTROL_PERMISSION) click("Share my screen only|Partilhar apenas o ecrã")
                    // Handle only the expected notification permission for this test app.
                    device.wait(Until.findObject(By.res(Pattern.compile(".*permissioncontroller:id/permission_allow_button"))),2000)?.click()
                    val projection = device.wait(Until.findObject(By.res("com.android.systemui","start_button")),10000)
                    if(projection!=null) projection.click() else click("Start now|Share screen|Start|Iniciar agora|Partilhar ecrã|Iniciar")
                    until { Runtime.session!!.state.value.stage==Stage.SHARING }
                    until { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id==1 } }
                    context.startActivity(Intent().setClassName("app.betterdesk.motionfixture","app.betterdesk.motionfixture.MotionActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                    assertNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Type|Escrever"))),10000))
                    device.waitForIdle()
                    device.takeScreenshot(File(context.filesDir,"relay-viewer.png"))
                    // A controlled desktop fixture receives this input; no unrelated PC UI is touched.
                    click("Type|Escrever")
                    val text = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg("app.betterdesk")),5000) ?: error("Text field unavailable")
                    text.text = "BetterDesk relay test"
                    click("Send|Enviar")
                    File(context.filesDir,"relay-progress.txt").writeText("Input sent")
                    Thread.sleep(3000)
                    click("Disconnect|Desligar")
                    until { Runtime.session!!.state.value.stage==Stage.ENDED }
                }
            } catch(e: Throwable) {
                device.takeScreenshot(File(context.filesDir,"relay-failure.png"))
                device.dumpWindowHierarchy(File(context.filesDir,"relay-failure.xml"))
                throw e
            } finally {
                codeFile.delete()
                scenario.onActivity { Runtime.session?.stop() }
            }
        }
    }
}
