package testigo.video

import testigo.TestigoException
import testigo.maestro.FlowFile
import testigo.maestro.FlowHeader
import testigo.policy.FlowLinter
import testigo.process.CommandResult
import testigo.process.CommandRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@DisabledOnOs(OS.WINDOWS)
class VideoTest {

    @TempDir
    lateinit var tmp: Path

    /** adb simulado: `pull` crea el archivo local; `pkill` ejecuta [onPkill]. */
    private class FakeAdb(private val onPkill: () -> Unit = {}) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            synchronized(commands) { commands += command }
            val args = command.drop(3)
            if (args.first() == "pull") Path.of(args[2]).writeText("mp4")
            if (args.getOrNull(1) == "pkill") onPkill()
            return CommandResult(0, "", "")
        }
    }

    private class FakeRecorder : Recorder {
        val calls = mutableListOf<String>()
        override fun start() = synchronized(calls) { calls += "start" }
        override fun stop() = synchronized(calls) { calls += "stop" }
        override fun pull(dir: Path): List<Path> = emptyList()
    }

    @Test
    fun `graba en segmentos y los trae como video-1, video-2…`() {
        val adb = FakeAdb()
        val launched = mutableListOf<List<String>>()
        val recorder = ScreenRecorder(
            adb = Path.of("/sdk/platform-tools/adb"),
            serial = "emulator-5554",
            bitrateMbps = 4,
            runner = adb,
            minSegment = 200.milliseconds,
            tail = Duration.ZERO,
            settle = Duration.ZERO,
            startProcess = { command ->
                synchronized(launched) { launched += command }
                ProcessBuilder("sleep", "0.5").start()
            },
        )

        recorder.start()
        Thread.sleep(1_300)
        recorder.stop()
        val videos = recorder.pull(tmp)

        assertTrue(launched.size >= 2, launched.toString())
        assertEquals((1..launched.size).map { "video-$it.mp4" }, videos.map { it.fileName.toString() })
        assertTrue(launched.first().containsAll(listOf("screenrecord", "--bit-rate", "4000000", "--time-limit", "170")), launched.first().toString())
        assertTrue(adb.commands.last().containsAll(listOf("shell", "rm", "-f")), "borra los videos del dispositivo")
    }

    @Test
    fun `una grabacion corta es un solo video mp4 y se detiene con SIGINT`() {
        var process: Process? = null
        val adb = FakeAdb(onPkill = { process?.destroy() })
        val recorder = ScreenRecorder(
            adb = Path.of("/sdk/platform-tools/adb"),
            serial = "emulator-5554",
            bitrateMbps = 8,
            runner = adb,
            tail = Duration.ZERO,
            settle = Duration.ZERO,
            startProcess = { ProcessBuilder("sleep", "30").start().also { process = it } },
        )

        recorder.start()
        recorder.stop()
        recorder.stop()

        assertEquals(listOf("video.mp4"), recorder.pull(tmp).map { it.fileName.toString() })
        assertEquals(1, adb.commands.count { "pkill" in it }, "stop se puede llamar dos veces")
        assertTrue(adb.commands.any { it.containsAll(listOf("pkill", "-INT", "screenrecord")) })
    }

    @Test
    fun `antes de parar sigue grabando y cambia la pantalla para que entren los ultimos cuadros`() {
        var process: Process? = null
        val adb = FakeAdb(onPkill = { process?.destroy() })
        val recorder = ScreenRecorder(
            adb = Path.of("/sdk/platform-tools/adb"),
            serial = "emulator-5554",
            bitrateMbps = 4,
            runner = adb,
            tail = 300.milliseconds,
            settle = Duration.ZERO,
            startProcess = { ProcessBuilder("sleep", "30").start().also { process = it } },
        )

        recorder.start()
        val asked = System.nanoTime()
        recorder.stop()
        val waited = (System.nanoTime() - asked) / 1_000_000

        val commands = adb.commands.map { it.drop(3).joinToString(" ") }
        val toggles = commands.indexOfFirst { "settings put system pointer_location 1" in it }
        val pkill = commands.indexOfFirst { it.startsWith("shell pkill -INT screenrecord") }
        assertTrue(waited >= 300, "graba la cola antes de parar: $waited ms")
        assertTrue(toggles in 0 until pkill, commands.toString())
        assertTrue(commands[toggles].endsWith("settings put system pointer_location 0; sleep 0.12"), "la barra queda como estaba: ${commands[toggles]}")
        assertEquals(3, Regex("pointer_location 1").findAll(commands[toggles]).count(), "3 ciclos, 6 cambios de pantalla")
        recorder.pull(tmp)
    }

    @Test
    fun `si screenrecord no arranca, no reintenta y avisa`() {
        val launched = mutableListOf<List<String>>()
        val recorder = ScreenRecorder(
            adb = Path.of("/sdk/platform-tools/adb"),
            serial = "emulator-5554",
            bitrateMbps = 4,
            runner = FakeAdb(),
            startProcess = { command ->
                launched += command
                ProcessBuilder("sh", "-c", "exit 1").start().also { it.waitFor() }
            },
        )

        assertThrows(TestigoException::class.java) { recorder.start() }
        assertEquals(1, launched.size)
    }

    @Test
    fun `el wrapper llama a los hooks, corre el flow real y respeta la politica`() {
        val flow = tmp.resolve("abrir_app.yaml").also { it.writeText("appId: com.empresa.app\n---\n- launchApp\n") }
        val yaml = VideoWrapper.content(
            appId = "com.empresa.app",
            name = "abrir \"app\"",
            flowFile = flow.fileName.toString(),
            startUrl = "http://127.0.0.1:41234/start?token=abc",
            stopUrl = "http://127.0.0.1:41234/stop?token=abc",
        )
        val wrapper = tmp.resolve(".testigo-video-abrir_app.yaml").also { it.writeText(yaml) }

        assertTrue(yaml.contains("appId: \"com.empresa.app\""), yaml)
        assertTrue(yaml.contains("name: \"abrir \\\"app\\\"\""), yaml)
        assertTrue(yaml.contains("onFlowStart:\n  - evalScript: \${http.get('http://127.0.0.1:41234/start?token=abc')}"), yaml)
        assertTrue(yaml.contains("onFlowComplete:\n  - evalScript: \${http.get('http://127.0.0.1:41234/stop?token=abc')}"), yaml)
        assertTrue(yaml.endsWith("---\n- runFlow: \"abrir_app.yaml\"\n"), yaml)
        assertTrue(FlowLinter().lint(listOf(wrapper)).passed, "los hooks solo van a localhost")
    }

    @Test
    fun `los hooks exigen el token y el wrapper se borra al cerrar`() {
        val flow = tmp.resolve("abrir_app.yaml").also { it.writeText("appId: com.empresa.app\n---\n- launchApp\n") }
        val recorder = FakeRecorder()
        val session = VideoSession(recorder, FlowFile(flow, FlowHeader(appId = "com.empresa.app")), "com.empresa.app")
        val startUrl = Regex("http://127\\.0\\.0\\.1:\\d+/start\\?token=[0-9a-f]+").find(session.wrapper.readText())!!.value

        assertEquals(403, status(startUrl.substringBefore("?token=") + "?token=otro"))
        assertEquals(200, status(startUrl))
        assertEquals(listOf("start"), recorder.calls)

        session.close()
        assertFalse(session.wrapper.exists())
    }

    private fun status(url: String): Int {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
