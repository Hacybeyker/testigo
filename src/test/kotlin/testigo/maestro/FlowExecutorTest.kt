package testigo.maestro

import testigo.TestigoException
import testigo.ExitCode
import testigo.config.RedactConfig
import testigo.config.RedactPreset
import testigo.logs.LogCollector
import testigo.logs.LogOutcome
import testigo.redact.EvidenceStatus
import testigo.redact.Leak
import testigo.redact.RedactRules
import testigo.redact.Redactor
import testigo.video.Recorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Usa un `maestro` falso (un script de shell) que se comporta como Maestro 2.11.0 con `--format HTML-DETAILED`. */
@DisabledOnOs(OS.WINDOWS)
class FlowExecutorTest {

    @TempDir
    lateinit var tmp: Path

    private fun fakeMaestro(): Path {
        val script = tmp.resolve("bin").createDirectories().resolve("maestro")
        script.writeText(
            """
            #!/bin/sh
            log="${'$'}(dirname "${'$'}0")/calls.txt"
            echo "${'$'}* MAESTRO_API_URL=${'$'}MAESTRO_API_URL" >> "${'$'}log"
            if [ "${'$'}1" = "--version" ]; then echo "maestro"; echo "2.11.0"; exit 0; fi
            report=""; out=""; flow=""
            while [ ${'$'}# -gt 0 ]; do
              case "${'$'}1" in
                --output) report="${'$'}2"; shift 2;;
                --test-output-dir) out="${'$'}2"; shift 2;;
                --device|--format|-e) shift 2;;
                --no-reinstall-driver|test) shift;;
                *) flow="${'$'}1"; shift;;
              esac
            done
            name="${'$'}(basename "${'$'}flow" .yaml)"
            stop=""
            if grep -q "/start?token=" "${'$'}flow"; then
              name="${'$'}(sed -n 's/^- runFlow: "\(.*\)\.yaml"${'$'}/\1/p' "${'$'}flow")"
              stop="${'$'}(grep -o 'http://127.0.0.1:[0-9]*/stop?token=[0-9a-f]*' "${'$'}flow")"
              curl -s "${'$'}(grep -o 'http://127.0.0.1:[0-9]*/start?token=[0-9a-f]*' "${'$'}flow")" > /dev/null
            fi
            mkdir -p "${'$'}out/2026-10-16_143012/${'$'}name"
            echo "log" > "${'$'}out/2026-10-16_143012/maestro.log"
            echo "{}" > "${'$'}out/2026-10-16_143012/${'$'}name/commands.json"
            mkdir -p "${'$'}out/2026-10-16_143012/${'$'}name/logs"
            echo "logs de otras apps" > "${'$'}out/2026-10-16_143012/${'$'}name/logs/device-logcat.txt"
            echo "<html>reporte</html>" > "${'$'}report"
            status=0
            case "${'$'}name" in
              *falla*) echo "Input text Secreto123"; echo "[Failed] ${'$'}name (2s) (Assertion is false: \"Hola\" is visible)"; status=1;;
              *jwt*)
                echo "Respuesta: $JWT"
                echo '{"session":"$JWT"}' > "${'$'}out/2026-10-16_143012/${'$'}name/commands.json"
                echo "[Passed] ${'$'}name (1s)";;
              *intermitente*)
                # Falla la primera vez y pasa las siguientes.
                if [ -f "${'$'}(dirname "${'$'}0")/intermitente.ok" ]; then echo "[Passed] ${'$'}name (1s)"
                else touch "${'$'}(dirname "${'$'}0")/intermitente.ok"; echo "[Failed] ${'$'}name (2s) (Element not found: Guardar)"; status=1; fi;;
              *) echo "[Passed] ${'$'}name (1s)";;
            esac
            if [ -n "${'$'}stop" ]; then curl -s "${'$'}stop" > /dev/null; fi
            exit ${'$'}status
            """.trimIndent() + "\n",
        )
        script.toFile().setExecutable(true)
        return script
    }

    private fun flow(fileName: String, name: String?): FlowFile {
        val path = tmp.resolve("flows").createDirectories().resolve(fileName)
        path.writeText("appId: com.empresa.app\n---\n- launchApp\n")
        return FlowFile(path, FlowHeader(appId = "com.empresa.app", name = name))
    }

    @Test
    fun `cada flow tiene su carpeta con report y lo de Maestro, y un fallo da codigo 1`() {
        val maestro = MaestroCli(fakeMaestro())
        val executor = FlowExecutor(maestro, rules = RedactRules.DEFAULT, now = { LocalDateTime.of(2026, 10, 16, 14, 30, 12) })

        val result = executor.run(
            flows = listOf(flow("ok.yaml", "abrir_app"), flow("falla.yaml", null)),
            device = "emulator-5554",
            env = mapOf("PASSWORD" to "Secreto123"),
            evidenceRoot = tmp.resolve("evidence"),
            appId = "com.empresa.app",
        )

        assertEquals(ExitCode.FLOW_FAILED, result.exitCode)
        assertEquals(tmp.resolve("evidence/20261016-143012"), result.dir)
        assertEquals(listOf("abrir_app", "falla"), result.flows.map { it.name })
        assertEquals(listOf(FlowStatus.PASSED, FlowStatus.FAILED), result.flows.map { it.status })

        val ok = result.dir.resolve("abrir_app")
        assertTrue(ok.resolve("report.html").exists())
        assertTrue(ok.resolve("maestro/maestro.log").exists())
        assertTrue(ok.resolve("maestro/ok/commands.json").exists())
        assertFalse(ok.resolve("maestro/ok/logs/device-logcat.txt").exists(), "el logcat de todo el dispositivo no queda")
        assertEquals(listOf("maestro", "report.html"), ok.listDirectoryEntries().map { it.fileName.toString() }.sorted())

        val failed = result.flows[1]
        assertTrue(failed.reason!!.contains("Assertion is false"), failed.reason)
        assertTrue(failed.report!!.exists(), "Maestro deja el reporte también cuando el flow falla")
        val console = failed.dir.resolve("maestro/console.txt").readText()
        assertTrue(console.contains("Input text ****"), console)
        assertFalse(console.contains("Secreto123"), console)
    }

    @Test
    fun `la carpeta de la corrida lleva ticket y commit despues de la fecha`() {
        val executor = FlowExecutor(MaestroCli(fakeMaestro()), now = { LocalDateTime.of(2026, 10, 16, 14, 30, 12) })

        val result = executor.run(listOf(flow("ok.yaml", null)), "emulator-5554", emptyMap(), tmp.resolve("evidence"), "com.empresa.app", "_PROJ-123_a1b2c3d")

        assertEquals("20261016-143012_PROJ-123_a1b2c3d", result.id)
        assertEquals("2026-10-16T14:30:12", result.startedAt.toLocalDateTime().toString())
        assertEquals(FlowExecutor.GITIGNORE, tmp.resolve("evidence/.gitignore").readText(), "la evidencia nunca entra a un commit")
    }

    @Test
    fun `el driver se instala solo en el primer flow y Maestro corre con el entorno local-only`() {
        val script = fakeMaestro()
        FlowExecutor(MaestroCli(script)).run(
            flows = listOf(flow("uno.yaml", null), flow("dos.yaml", null)),
            device = "emulator-5554",
            env = emptyMap(),
            evidenceRoot = tmp.resolve("evidence"),
            appId = "com.empresa.app",
        )

        val calls = script.resolveSibling("calls.txt").readLines()
        assertEquals(2, calls.size)
        assertFalse(calls[0].contains("--no-reinstall-driver"), calls[0])
        assertTrue(calls[1].contains("--no-reinstall-driver"), calls[1])
        assertTrue(calls.all { "--device emulator-5554" in it && "--format HTML-DETAILED" in it }, calls.toString())
        assertTrue(calls.all { it.endsWith("MAESTRO_API_URL=http://127.0.0.1:9") }, calls.toString())
    }

    @Test
    fun `con repeat corre la lista en rondas, cada corrida en su carpeta, y distingue los intermitentes`() {
        val script = fakeMaestro()
        val progress = mutableListOf<String>()
        val executor = FlowExecutor(MaestroCli(script), now = { LocalDateTime.of(2026, 10, 16, 14, 30, 12) }, progress = { progress += it })

        val result = executor.run(
            flows = listOf(flow("ok.yaml", "abrir_app"), flow("intermitente.yaml", null)),
            device = "emulator-5554",
            env = emptyMap(),
            evidenceRoot = tmp.resolve("evidence"),
            appId = "com.empresa.app",
            repeat = 3,
        )

        assertEquals(listOf("abrir_app", "intermitente", "abrir_app", "intermitente", "abrir_app", "intermitente"), result.flows.map { it.name })
        assertEquals(listOf(1, 1, 2, 2, 3, 3), result.flows.map { it.attempt })
        assertEquals(result.dir.resolve("intermitente/2"), result.flows[3].dir)
        assertTrue(result.dir.resolve("abrir_app/3/report.html").exists())
        assertEquals(listOf("estable", "intermitente"), result.stability.map { it.label })
        assertEquals(listOf("intermitente"), result.flaky)
        assertEquals("intermitente #1", result.label(result.flows[1]))
        assertEquals(5, result.passed)
        assertEquals(ExitCode.FLOW_FAILED, result.exitCode, "un intermitente es un fallo")
        assertTrue("▶ intermitente (4/6 · ronda 2 de 3)" in progress, progress.toString())

        val calls = script.resolveSibling("calls.txt").readLines()
        assertEquals(6, calls.size)
        assertFalse(calls[0].contains("--no-reinstall-driver"), "el driver se instala solo en la primera corrida")
        assertTrue(calls.drop(1).all { it.contains("--no-reinstall-driver") }, calls.toString())
    }

    private class FakeRecorder : Recorder {
        val calls = mutableListOf<String>()

        override fun start() {
            synchronized(calls) { calls += "start" }
        }

        override fun stop() {
            synchronized(calls) { calls += "stop" }
        }

        override fun pull(dir: Path): List<Path> {
            synchronized(calls) { calls += "pull" }
            return listOf(dir.resolve("video.mp4").also { it.writeText("mp4") })
        }
    }

    @Test
    fun `con video graba entre los hooks del flow, tambien si falla, y borra el wrapper`() {
        val recorders = mutableListOf<FakeRecorder>()
        val executor = FlowExecutor(MaestroCli(fakeMaestro()), recorder = { FakeRecorder().also { recorders += it } })

        val result = executor.run(
            flows = listOf(flow("ok.yaml", "abrir_app"), flow("falla.yaml", null)),
            device = "emulator-5554",
            env = emptyMap(),
            evidenceRoot = tmp.resolve("evidence"),
            appId = "com.empresa.app",
        )

        assertEquals(listOf(FlowStatus.PASSED, FlowStatus.FAILED), result.flows.map { it.status })
        result.flows.forEach { flow ->
            assertEquals(listOf(flow.dir.resolve("video.mp4")), flow.videos, flow.name)
            assertEquals(null, flow.videoNote, flow.name)
        }
        recorders.forEach { recorder ->
            assertEquals("start", recorder.calls.first())
            assertTrue("stop" in recorder.calls && recorder.calls.last() == "pull", recorder.calls.toString())
        }
        assertEquals(emptyList<String>(), tmp.resolve("flows").listDirectoryEntries(".testigo-video-*").map { it.fileName.toString() })
    }

    private class CrashingLogs : LogCollector {
        override fun start(dir: Path) = Unit

        override fun finish(dir: Path, redactor: Redactor): LogOutcome {
            val crash = dir.resolve("crash.txt").also { it.writeText("FATAL EXCEPTION") }
            return LogOutcome(logcat = null, crashFile = crash, crash = "java.lang.IllegalStateException: boom")
        }
    }

    @Test
    fun `un crash de la app hace fallar el flow aunque Maestro haya pasado`() {
        val executor = FlowExecutor(MaestroCli(fakeMaestro()), logs = { CrashingLogs() })

        val result = executor.run(listOf(flow("ok.yaml", "abrir_app")), "emulator-5554", emptyMap(), tmp.resolve("evidence"), "com.empresa.app")

        val flow = result.flows.single()
        assertEquals(FlowStatus.FAILED, flow.status)
        assertEquals(ExitCode.FLOW_FAILED, result.exitCode)
        assertEquals("crash de la app: java.lang.IllegalStateException: boom", flow.reason)
        assertEquals(flow.dir.resolve("crash.txt"), flow.crashFile)
    }

    @Test
    fun `sin sanitizar la evidencia queda tal cual y no hay leak scan`() {
        val result = FlowExecutor(MaestroCli(fakeMaestro()))
            .run(listOf(flow("con_jwt.yaml", null)), "emulator-5554", emptyMap(), tmp.resolve("e0"), "com.empresa.app")

        assertEquals(ExitCode.OK, result.exitCode)
        assertEquals(EvidenceStatus.SIN_SANITIZAR, result.evidenceStatus)
        assertTrue(result.flows.single().dir.resolve("maestro/console.txt").readText().contains("Respuesta: $JWT"))
    }

    @Test
    fun `un JWT en la evidencia queda enmascarado y, sin el preset base, la corrida termina con codigo 4`() {
        val flows = listOf(flow("con_jwt.yaml", null))

        val masked = FlowExecutor(MaestroCli(fakeMaestro()), rules = RedactRules.DEFAULT)
            .run(flows, "emulator-5554", emptyMap(), tmp.resolve("e1"), "com.empresa.app")

        val flow = masked.flows.single()
        assertEquals(ExitCode.OK, masked.exitCode)
        assertEquals(EvidenceStatus.OK, masked.evidenceStatus)
        assertTrue(flow.dir.resolve("maestro/console.txt").readText().contains("Respuesta: ***"))
        assertEquals("{\"session\":\"***\"}\n", flow.dir.resolve("maestro/con_jwt/commands.json").readText())

        val withoutBase = FlowExecutor(MaestroCli(fakeMaestro()), rules = RedactRules.of(RedactConfig(presets = listOf(RedactPreset.PII))))
            .run(flows, "emulator-5554", emptyMap(), tmp.resolve("e2"), "com.empresa.app")

        assertEquals(ExitCode.LEAK, withoutBase.exitCode)
        assertEquals(EvidenceStatus.NO_APTA, withoutBase.evidenceStatus)
        assertEquals(
            listOf(Leak(Path.of("maestro/con_jwt/commands.json"), 1, "jwt"), Leak(Path.of("maestro/console.txt"), 1, "jwt")),
            withoutBase.leaks,
        )
    }

    @Test
    fun `con keep-raw los crudos de Maestro quedan en raw y el leak scan no los cuenta`() {
        val result = FlowExecutor(MaestroCli(fakeMaestro()), rules = RedactRules.DEFAULT, keepRaw = true)
            .run(listOf(flow("con_jwt.yaml", null)), "emulator-5554", emptyMap(), tmp.resolve("evidence"), "com.empresa.app")

        val dir = result.flows.single().dir
        assertTrue(dir.resolve("raw/maestro/console.txt").readText().contains("eyJ"))
        assertTrue(dir.resolve("raw/maestro/report.html").exists())
        assertEquals(ExitCode.OK, result.exitCode)
        assertTrue(result.keepRaw)
    }

    @Test
    fun `lee la version instalada`() {
        assertEquals("2.11.0", MaestroCli(fakeMaestro()).version())
    }

    @Test
    fun `busca maestro en el PATH y luego en el directorio del instalador`() {
        val script = fakeMaestro()
        val home = tmp.resolve("home")
        val installed = home.resolve(".maestro/bin").createDirectories().resolve("maestro")
        installed.writeText("#!/bin/sh\n")

        assertEquals(script, MaestroCli.locate(mapOf("PATH" to script.parent.toString()), home, "Mac OS X").executable)
        assertEquals(installed, MaestroCli.locate(mapOf("PATH" to "/no/existe"), home, "Mac OS X").executable)
        assertThrows(TestigoException::class.java) { MaestroCli.locate(mapOf("PATH" to "/no/existe"), tmp.resolve("otro"), "Linux") }
    }

    @Test
    fun `nombres de carpeta unicos y sin caracteres raros`() {
        val flows = listOf(flow("a.yaml", "Actualizar datos"), flow("b.yaml", "Actualizar datos"), flow("perfil.yaml", null))

        assertEquals(listOf("Actualizar_datos", "Actualizar_datos-2", "perfil"), FlowExecutor.uniqueNames(flows))
    }

    @Test
    fun `resume el motivo del fallo desde la consola`() {
        val console = """
            Running on emulator-5554
            ║  ✅ Launch app "com.empresa.app"
            [Failed] registro (5s) (Element not found: Text matching regex: Crear cuenta)

            1/1 Flow Failed
            ==== Debug output (logs & screenshots) ====
            /Users/qa/.maestro/tests/2026-10-16_143012
        """.trimIndent()

        assertEquals("[Failed] registro (5s) (Element not found: Text matching regex: Crear cuenta)", FlowExecutor.lastLines(console))
        assertEquals("12.3 s", FlowExecutor.format(kotlin.time.Duration.parse("12.34s")))
        assertEquals("1 min 12 s", FlowExecutor.format(kotlin.time.Duration.parse("72s")))
    }

    companion object {
        /** Un JWT de prueba (header, payload y firma inventados). */
        private const val JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJxYS11c2VyIn0.c2lnbmF0dXJhLWZhbHNh"
    }
}
