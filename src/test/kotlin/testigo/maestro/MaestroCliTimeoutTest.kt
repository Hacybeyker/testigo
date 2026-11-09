package testigo.maestro

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/** Qué le pasa a lo que lanza Maestro cuando se agota el tiempo máximo del flow. */
@DisabledOnOs(OS.WINDOWS)
class MaestroCliTimeoutTest {

    @TempDir
    lateinit var tmp: Path

    @AfterEach
    fun killLeftovers() {
        // Si la prueba falla, los procesos del maestro falso no deben quedar corriendo.
        listOf("principal.pid", "hijo.pid", "nieto.pid").map { tmp.resolve("bin/$it") }.filter { it.exists() }.forEach { file ->
            file.readText().trim().toLongOrNull()?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    private fun alive(pidFile: Path): Boolean =
        ProcessHandle.of(pidFile.readText().trim().toLong()).map { it.isAlive }.orElse(false)

    @Test
    fun `al agotarse el tiempo mata tambien a los hijos y a los nietos de Maestro, no solo a Maestro`() {
        // Un «maestro» que lanza un hijo (un subshell) y este un nieto (sleep), y se queda esperando.
        val script = tmp.resolve("bin").createDirectories().resolve("maestro")
        script.writeText(
            """
            #!/bin/sh
            dir="${'$'}(dirname "${'$'}0")"
            ( sleep 300 & echo ${'$'}! > "${'$'}dir/nieto.pid"; wait ) &
            echo ${'$'}! > "${'$'}dir/hijo.pid"
            sleep 300 &
            echo ${'$'}! > "${'$'}dir/principal.pid"
            wait
            """.trimIndent() + "\n",
        )
        script.toFile().setExecutable(true)
        val flow = tmp.resolve("flows").createDirectories().resolve("uno.yaml").also { it.writeText("appId: com.empresa.app\n---\n- launchApp\n") }

        val outcome = MaestroCli(script).test(
            MaestroTest(
                flow = flow,
                device = "emulator-5554",
                env = emptyMap(),
                report = tmp.resolve("report.html"),
                outputDir = tmp.resolve("out").createDirectories(),
                console = tmp.resolve("console.txt"),
                reinstallDriver = true,
                timeout = 2.seconds,
            ),
        )

        assertTrue(outcome.timedOut)
        val child = tmp.resolve("bin/hijo.pid")
        val grandchild = tmp.resolve("bin/nieto.pid")
        assertTrue(child.exists() && grandchild.exists(), "el maestro falso alcanzó a lanzar a sus procesos")
        val deadline = System.nanoTime() + 5_000_000_000L
        while ((alive(child) || alive(grandchild)) && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse(alive(grandchild), "el nieto (un adb, un driver…) no queda corriendo")
        assertFalse(alive(child), "ni el hijo")
    }
}
