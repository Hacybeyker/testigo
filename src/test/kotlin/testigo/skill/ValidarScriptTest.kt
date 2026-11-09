package testigo.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `validar.py` de la skill `testigo-flows`, corrido con el Python de la máquina contra un `testigo` y un `maestro` falsos que
 * dicen que todo está bien: así solo se prueban las revisiones del propio script.
 */
@DisabledOnOs(OS.WINDOWS)
class ValidarScriptTest {

    @TempDir
    lateinit var tmp: Path

    private val script: Path = Path.of("skills/testigo-flows/scripts/validar.py").toAbsolutePath()

    private fun python(): String? = listOf("python3", "python").firstOrNull { name ->
        runCatching { ProcessBuilder(name, "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
    }

    private fun flow(name: String, steps: String): String {
        val dir = tmp.resolve("flows").createDirectories()
        dir.resolve(name).writeText("appId: com.empresa.app\n---\n$steps")
        return "flows/$name"
    }

    /** El código de salida y la salida de `validar.py <flow>`. [python] es el intérprete y [prefix] los argumentos antes del script. */
    private fun validar(flow: String, prefix: List<String> = listOf("-I")): Pair<Int, String> {
        val python = python()
        assumeTrue(python != null, "no hay python3 en esta máquina")
        val bin = tmp.resolve("bin").createDirectories()
        bin.resolve("testigo").also { it.writeText("#!/bin/sh\necho 'Política local-only: OK'\n") }.toFile().setExecutable(true)
        bin.resolve("maestro").also { it.writeText("#!/bin/sh\nexit 0\n") }.toFile().setExecutable(true)
        val output = tmp.resolve("salida.txt")
        val builder = ProcessBuilder(listOf(python!!) + prefix + listOf(script.toString(), flow))
            .directory(tmp.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
        builder.environment()["PATH"] = bin.toString() + ":" + System.getenv("PATH")
        val process = builder.start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "validar.py no terminó")
        return process.exitValue() to output.readText()
    }

    @Test
    fun `detecta las tres formas de takeScreenshot con una ruta que Maestro rechaza`() {
        val flow = flow(
            "capturas.yaml",
            """
            |- launchApp
            |- takeScreenshot:
            |    path: ../afuera
            |- takeScreenshot:
            |    label: paso
            |    path: /absoluta
            |- takeScreenshot: {label: b, path: ../en-linea}
            |- takeScreenshot: ../corta
            |- takeScreenshot:
            |    path: "../entre-comillas"
            |
            """.trimMargin(),
        )

        val (code, out) = validar(flow)

        assertEquals(1, code, out)
        for ((line, target) in listOf(5 to "../afuera", 8 to "/absoluta", 9 to "../en-linea", 10 to "../corta", 12 to "../entre-comillas")) {
            assertTrue("capturas.yaml:$line takeScreenshot con ruta fuera de la carpeta de la corrida ($target)" in out, "falta $target (línea $line):\n$out")
        }
    }

    @Test
    fun `no se queja de lo que Maestro acepta ni de lo comentado`() {
        val flow = flow(
            "bien.yaml",
            """
            |- launchApp
            |- takeScreenshot: paso1
            |- takeScreenshot: antes/paso2
            |- takeScreenshot:
            |    label: tres
            |    path: antes/paso3
            |- takeScreenshot: {label: cuatro, path: paso4}
            |- takeScreenshot
            |# - takeScreenshot: ../comentado
            |- tapOn:
            |    text: "Guardar"
            |    path: ../esto-no-es-una-captura
            |
            """.trimMargin(),
        )

        val (code, out) = validar(flow)

        assertEquals(0, code, out)
        assertFalse("captura FALLA" in out, out)
    }

    /**
     * `Path.is_relative_to` es de Python 3.9 y la skill promete Python 3.8. Se simula quitándola antes de correr el script con
     * `runpy`, así la prueba falla en cualquier versión de Python y no solo en una 3.8 de verdad.
     */
    @Test
    fun `no usa Path is_relative_to, que no existe en Python 3_8`() {
        val flow = flow("simple.yaml", "- launchApp\n")
        val without = listOf(
            "-I",
            "-c",
            "import pathlib, runpy, sys; del pathlib.PurePath.is_relative_to; sys.argv = sys.argv[1:]; runpy.run_path(sys.argv[0], run_name='__main__')",
        )

        val (code, out) = validar(flow, without)

        assertEquals(0, code, out)
        assertFalse("Traceback" in out || "is_relative_to" in out, out)
        assertTrue("2. sintaxis OK: $flow" in out, out)
    }
}
