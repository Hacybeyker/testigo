package testigo.report

import testigo.ExitCode
import testigo.android.DeviceInfo
import testigo.artifact.Artifact
import testigo.artifact.ArtifactFormat
import testigo.artifact.ArtifactInfo
import testigo.maestro.FlowFile
import testigo.maestro.FlowHeader
import testigo.maestro.FlowResult
import testigo.maestro.FlowStatus
import testigo.maestro.RunResult
import testigo.network.NetworkOutcome
import testigo.network.NetworkSummary
import testigo.redact.RedactRules
import testigo.redact.Redactor
import testigo.util.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RunReportTest {

    @TempDir
    lateinit var tmp: Path

    private fun network(vararg alerts: NetworkSummary.Alert, requests: Int = 8) = NetworkOutcome(
        summary = NetworkSummary(requests, alerts.count { it.error }, alerts.count { it.slow }, 3_000, emptyList(), emptyList(), alerts.toList()),
    )

    private fun flow(name: String, status: FlowStatus, network: NetworkOutcome? = null, reason: String? = null, crash: String? = null) =
        FlowResult(
            name = name,
            flow = FlowFile(tmp.resolve("flows/$name.yaml"), FlowHeader(appId = "com.empresa.app")),
            dir = tmp.resolve("evidence/run/$name"),
            status = status,
            duration = 52.seconds,
            report = null,
            reason = reason,
            crash = crash,
            network = network,
        )

    private val serverError = NetworkSummary.Alert("PUT", "api.qa.example.com", "/profile", 500, 640, error = true, slow = false)

    private fun result(sanitized: Boolean = false) = RunResult(
        dir = tmp.resolve("evidence/20261016-213000_PROJ-123_a1b2c3d").createDirectories(),
        flows = listOf(
            flow("registro", FlowStatus.PASSED, network(requests = 11)),
            flow("actualizar_datos", FlowStatus.FAILED, network(serverError), reason = "[Failed] actualizar_datos (5s) (Element not found: Guardado)"),
            flow("login", FlowStatus.FAILED, reason = "crash de la app: java.lang.IllegalStateException: boom", crash = "java.lang.IllegalStateException: boom"),
        ),
        duration = 2_500.milliseconds + 120.seconds,
        sanitized = sanitized,
        startedAt = ZonedDateTime.of(2026, 10, 16, 21, 30, 0, 0, ZoneOffset.ofHours(-5)),
    )

    private fun context(ci: Boolean = false) = RunContext(
        profile = "evidencia",
        device = DeviceInfo("NJ4", "21091116AG", "13", 33),
        app = ArtifactInfo(Artifact(tmp.resolve("app-qa.aab"), ArtifactFormat.AAB), "com.empresa.app", 340, "3.4.0", true, "ab".repeat(32)),
        maestroVersion = "2.11.0",
        git = GitInfo("app", "feature/PROJ-123-perfil", "a1b2c3d", dirty = false, ticket = "PROJ-123"),
        options = mapOf("profile" to "evidencia", "redact" to false, "env_keys" to listOf("PASSWORD")),
        ci = ci,
    )

    @Test
    fun `un 500 del backend aparece en las alertas del summary`() {
        val summary = RunReport.summary(result(), context(), tmp)

        assertTrue(summary.startsWith("## E2E · PROJ-123 · a1b2c3d · com.empresa.app 3.4.0 (340) · AAB\n"), summary)
        assertTrue(summary.contains("21091116AG · Android 13 (API 33) · perfil evidencia · 3 flows · 2 fallaron · 2 min 2 s"), summary)
        assertTrue(summary.contains("| actualizar_datos | ❌ | 52.0 s | 8 | PUT /profile → 500 |"), summary)
        assertTrue(summary.contains("| login | ❌ | 52.0 s | — | crash: IllegalStateException |"), summary)
        assertTrue(summary.contains("| registro | ✅ | 52.0 s | 11 | — |"), summary)
        assertTrue(
            summary.contains(
                "### Alertas de red · actualizar_datos\n| Método | Endpoint | Status | Veces | Tiempo máx |\n" +
                    "| --- | --- | --- | --- | --- |\n| PUT | /profile | 500 | 1 | 640 ms |",
            ),
            summary,
        )
        assertTrue(summary.contains("- **actualizar_datos**: [Failed] actualizar_datos (5s) (Element not found: Guardado)"), summary)
        assertTrue(summary.contains("Evidencia sin sanitizar"), summary)
    }

    @Test
    fun `las alertas iguales se agrupan con su cantidad`() {
        fun offline(path: String) = NetworkSummary.Alert("GET", "api.themoviedb.org", path, null, null, error = true, slow = false, errorType = "UnknownHostException")
        val alerts = arrayOf(offline("/3/discover/movie"), offline("/3/discover/movie"), offline("/3/movie/upcoming"), offline("/3/discover/movie"))
        val run = result().copy(flows = listOf(flow("sin_red", FlowStatus.PASSED, network(*alerts))))

        val summary = RunReport.summary(run, context(), tmp)

        assertTrue(
            summary.contains(
                "| sin_red | ✅ | 52.0 s | 8 | GET /3/discover/movie → sin respuesta (UnknownHostException) ×3, " +
                    "GET /3/movie/upcoming → sin respuesta (UnknownHostException) |",
            ),
            summary,
        )
        assertTrue(summary.contains("| GET | /3/discover/movie | sin respuesta (UnknownHostException) | 3 | — |"), summary)
        assertEquals(
            listOf(
                "GET sin respuesta (UnknownHostException) · api.themoviedb.org/3/discover/movie ×3",
                "GET sin respuesta (UnknownHostException) · api.themoviedb.org/3/movie/upcoming",
            ),
            run.flows.single().network!!.summary!!.alertLines(),
        )
    }

    @Test
    fun `metadata trae app, dispositivo, git, flows con alertas y estado de la evidencia`() {
        val metadata = Json.parse(Json.write(RunReport.metadata(result(sanitized = true), context(), tmp))) as Map<*, *>

        assertEquals("20261016-213000_PROJ-123_a1b2c3d", metadata["run_id"])
        assertEquals("2026-10-16T21:30:00-05:00", metadata["started_at"])
        assertEquals(122.5, metadata["duration_s"])
        assertEquals("PROJ-123", (metadata["git"] as Map<*, *>)["ticket"])
        assertEquals(340L, (metadata["app"] as Map<*, *>)["version_code"])
        assertEquals(33L, (metadata["device"] as Map<*, *>)["sdk"])
        val flows = metadata["flows"] as List<*>
        val alert = (((flows[1] as Map<*, *>)["network"] as Map<*, *>)["alerts"] as List<*>).single() as Map<*, *>
        assertEquals(500L, alert["status"])
        assertEquals("failed", (flows[2] as Map<*, *>)["status"])
        assertEquals(1L, metadata["passed"])
        assertEquals(ExitCode.FLOW_FAILED.code.toLong(), metadata["exit_code"])
        assertEquals("OK", metadata["evidence_status"])
        assertEquals(listOf("PASSWORD"), (metadata["options"] as Map<*, *>)["env_keys"])
    }

    @Test
    fun `escribe summary y metadata en la carpeta de la corrida, y junit solo con --ci`() {
        val withoutCi = RunReport.write(result(), context(), tmp, Redactor.NONE)
        assertTrue(withoutCi.summary.exists() && withoutCi.metadata.exists())
        assertNull(withoutCi.junit)

        val files = RunReport.write(result(), context(ci = true), tmp, Redactor.NONE)
        val junit = files.junit!!.readText()
        assertTrue(junit.contains("""<testsuite name="testigo" tests="3" failures="2" errors="0" time="122.5" timestamp="2026-10-16T21:30:00">"""), junit)
        assertTrue(junit.contains("""<testcase name="registro" classname="testigo.registro" time="52.0"/>"""), junit)
        assertTrue(junit.contains("""<failure message="crash de la app: java.lang.IllegalStateException: boom">"""), junit)
    }

    @Test
    fun `con --redact el resumen tambien sale sanitizado`() {
        val leaky = result().copy(flows = listOf(flow("login", FlowStatus.FAILED, reason = "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJxYSJ9.c2lnbmF0dXJh")))

        val files = RunReport.write(leaky, context(), tmp, Redactor(RedactRules.DEFAULT))

        assertFalse(files.summary.readText().contains("eyJ"))
        assertFalse(files.metadata.readText().contains("eyJ"))
    }
}
