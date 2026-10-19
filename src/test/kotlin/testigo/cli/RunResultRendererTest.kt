package testigo.cli

import testigo.ExitCode
import testigo.maestro.FlowFile
import testigo.maestro.FlowHeader
import testigo.maestro.FlowResult
import testigo.maestro.FlowStatus
import testigo.maestro.RunResult
import testigo.redact.Leak
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class RunResultRendererTest {

    private val cwd = Path.of("/suite")

    private fun flow(leaks: List<Leak> = emptyList()) = FlowResult(
        name = "login",
        flow = FlowFile(cwd.resolve("flows/login.yaml"), FlowHeader(appId = "com.empresa.app", name = "login")),
        dir = cwd.resolve("evidence/20261016-170000/login"),
        status = FlowStatus.PASSED,
        duration = 12.seconds,
        report = null,
        reason = null,
        leaks = leaks,
    )

    @Test
    fun `con leaks la evidencia queda no apta, dice donde y que regla, y sale con codigo 4`() {
        val leaks = listOf(Leak(Path.of("logcat.txt"), 120, "jwt"), Leak(Path.of("maestro/console.txt"), 3, "clave password"))
        val result = RunResult(cwd.resolve("evidence/20261016-170000"), listOf(flow(leaks)), 13.seconds, sanitized = true)

        val text = RunResultRenderer.render(result, cwd)

        assertEquals(ExitCode.LEAK, result.exitCode)
        assertTrue(text.contains("! leak scan: 2 datos sin enmascarar"), text)
        assertTrue(text.contains("        logcat.txt:120  jwt"), text)
        assertTrue(text.contains("        maestro/console.txt:3  clave password"), text)
        assertTrue(text.contains("Evidencia NO APTA"), text)
    }

    @Test
    fun `con repeat muestra cuantas veces paso cada flow y el detalle de las corridas que fallaron`() {
        val dir = cwd.resolve("evidence/20261016-170000")
        val failed = flow().copy(
            status = FlowStatus.FAILED,
            reason = "[Failed] login (5s) (Element not found: Hola)",
            attempt = 2,
            dir = dir.resolve("login/2"),
            report = dir.resolve("login/2/report.html"),
        )
        val result = RunResult(dir, listOf(flow().copy(attempt = 1), failed, flow().copy(attempt = 3)), 40.seconds, repeat = 3)

        val text = RunResultRenderer.render(result, cwd)

        assertEquals(ExitCode.FLOW_FAILED, result.exitCode)
        assertTrue(text.contains("Resultado: 2 de 3 corridas pasaron · 1 flow × 3 · 1 intermitente · 40.0 s"), text)
        assertTrue(text.contains("  ! login    2/3  intermitente  media 12.0 s"), text)
        assertTrue(text.contains("      #2  falló · 12.0 s"), text)
        assertTrue(text.contains("          [Failed] login (5s) (Element not found: Hola)"), text)
        assertTrue(text.contains("          evidence/20261016-170000/login/2/report.html"), text)
        assertFalse(text.contains("#1") || text.contains("#3"), "las corridas que pasaron no se detallan")
    }

    @Test
    fun `sin leaks no hay aviso`() {
        val result = RunResult(cwd.resolve("evidence/20261016-170000"), listOf(flow()), 13.seconds)

        val text = RunResultRenderer.render(result, cwd, cwd.resolve("evidence/20261016-170000/summary.md"))

        assertEquals(ExitCode.OK, result.exitCode)
        assertTrue(text.contains("Resumen:   evidence/20261016-170000/summary.md"), text)
        assertFalse(text.contains("leak scan") || text.contains("NO APTA"), text)
    }
}
