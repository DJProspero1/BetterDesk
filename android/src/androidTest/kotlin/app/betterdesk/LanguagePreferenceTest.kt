package app.betterdesk

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.betterdesk.core.HelpSession
import org.junit.Assert.*
import org.junit.Test

class LanguagePreferenceTest {
    @Test fun flagChoiceSurvivesActivityRecreationAndNewPlatformInstance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val previous = AndroidPlatform(context).languageTag
        fun resetRuntime() = instrumentation.runOnMainSync {
            Runtime.session?.dispose()
            Runtime.platform = AndroidPlatform(context)
            Runtime.session = HelpSession(Runtime.platform!!, relayFactory = { null })
        }
        try {
            AndroidPlatform(context).languageTag = "en"
            resetRuntime()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val flag = device.wait(Until.findObject(By.desc("English. Mudar idioma para português")),10000)
                assertNotNull(flag)
                flag!!.click()
                assertNotNull(device.wait(Until.findObject(By.text("Ligar")),10000))
                assertEquals("pt", AndroidPlatform(context).languageTag)
                scenario.recreate()
                assertNotNull(device.wait(Until.findObject(By.desc("Português. Change language to English")),10000))
            }
            resetRuntime()
            ActivityScenario.launch(MainActivity::class.java).use {
                assertNotNull(device.wait(Until.findObject(By.text("Ligar")),10000))
                assertEquals("pt", Runtime.platform!!.languageTag)
            }
        } finally {
            instrumentation.runOnMainSync {
                Runtime.session?.dispose(); Runtime.session = null; Runtime.platform = null
            }
            AndroidPlatform(context).languageTag = previous
        }
    }
}
