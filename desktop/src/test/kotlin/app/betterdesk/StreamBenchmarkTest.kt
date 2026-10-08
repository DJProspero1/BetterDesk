package app.betterdesk

import androidx.compose.ui.awt.ComposePanel
import app.betterdesk.core.*
import app.betterdesk.desktop.DesktopPlatform
import app.betterdesk.ui.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.*
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import kotlin.test.assertTrue

/** Opt-in, visible motion fixture. Measures native capture -> real TLS -> decode -> Compose draw. */
class StreamBenchmarkTest {
    @Test fun `measure a continuously changing desktop through the actual viewer`() {
        assumeTrue(System.getenv("BETTERDESK_STREAM_BENCHMARK") == "1")
        val digest = MessageDigest.getInstance("SHA-256")
        val unique = ConcurrentHashMap.newKeySet<String>()
        val sourcePlatform = DesktopPlatform()
        val source = HelpSession(sourcePlatform, { listOf("127.0.0.1") }, InetAddress.getLoopbackAddress())
        val viewerPlatform = object : RemotePlatform by DesktopPlatform() {
            override fun decode(frame: Packet.Frame): androidx.compose.ui.graphics.ImageBitmap? {
                unique.add(java.util.Base64.getEncoder().encodeToString(digest.digest(frame.jpeg)))
                return superDecode.decode(frame)
            }
            private val superDecode = DesktopPlatform()
        }
        val viewer = HelpSession(viewerPlatform, { listOf("127.0.0.1") }, InetAddress.getLoopbackAddress())
        var motion: JFrame? = null
        var display: JFrame? = null
        var timer: Timer? = null
        runBlocking {
            try {
                withContext(Dispatchers.Main) {
                    val panel = object : JPanel() {
                        override fun paintComponent(g: Graphics) {
                            super.paintComponent(g)
                            val tick = System.nanoTime() / 1_000_000
                            g.color = Color(230, 240, 235); g.fillRect(0, 0, width, height)
                            g.color = Color(34, 99, 78)
                            repeat(16) { i -> val x = ((i * 90 + tick / 3) % width.coerceAtLeast(1)).toInt(); g.fillRect(x, 100, 35, height - 160) }
                            g.font = Font("Segoe UI", Font.PLAIN, 26); g.drawString("BetterDesk local motion test • $tick", 25, 55)
                        }
                    }
                    motion = JFrame("BetterDesk motion fixture").apply { contentPane = panel; setBounds(0, 0, 1200, 900); isVisible = true }
                    timer = Timer(5) { panel.repaint() }.apply { start() }
                    val compose = ComposePanel().apply { setContent { BetterDeskTheme { BetterDeskApp(viewer) } } }
                    display = JFrame("BetterDesk measured receiver").apply { contentPane = compose; setBounds(1210, 0, 1100, 950); isVisible = true }
                    viewer.createInvitation("Local benchmark")
                }
                withTimeout(15_000) { while (viewer.state.value.stage != Stage.WAITING) delay(20) }
                withContext(Dispatchers.Main) { source.acceptInvitation(viewer.state.value.invitation!!.link()); source.allowHelp() }
                withTimeout(15_000) { while (viewer.state.value.stage != Stage.VIEWING) delay(20) }
                delay(3000)
                unique.clear(); viewer.performance.reset()
                delay(30_000)
                val measured = viewer.performance.snapshot()
                val report = "${sourcePlatform.captureBackend}: Windows full desktop -> TLS loopback -> Compose draw: ${measured.receivedFps} received FPS, ${measured.presentedFps} unique frame IDs drawn/s, ${unique.size / measured.seconds} distinct JPEGs/s, ${measured.seconds} seconds. P95 draw gap ${measured.p95DrawGapMs}ms; JPEG payload ${measured.encodedMbps} Mbps.\n"
                File("build/reports/stream-benchmark.txt").apply { parentFile.mkdirs(); writeText(report) }
                println(report)
                assertTrue(measured.received > 0 && measured.presented > 0 && unique.size > 10)
            } finally {
                withContext(Dispatchers.Main) { source.dispose(); viewer.dispose(); timer?.stop(); motion?.dispose(); display?.dispose() }
            }
        }
    }
}
