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
    fun `sin leaks no hay aviso`() {
        val result = RunResult(cwd.resolve("evidence/20261016-170000"), listOf(flow()), 13.seconds)

        val text = RunResultRenderer.render(result, cwd, cwd.resolve("evidence/20261016-170000/summary.md"))

        assertEquals(ExitCode.OK, result.exitCode)
        assertTrue(text.contains("Resumen:   evidence/20261016-170000/summary.md"), text)
        assertFalse(text.contains("leak scan") || text.contains("NO APTA"), text)
    }
}
