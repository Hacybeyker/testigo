package testigo.process

import testigo.TestigoException
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.time.Duration

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0

    /** stdout y stderr juntos, para mensajes de error. */
    val output: String get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n").trim()
}

/** Ejecuta herramientas externas (adb, aapt2, bundletool). Una interfaz para poder probar sin dispositivo. */
fun interface CommandRunner {
    fun run(command: List<String>, timeout: Duration): CommandResult
}

class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, timeout: Duration): CommandResult {
        // La salida va a archivos temporales: leer de pipes sin hilos puede bloquear el proceso si escribe mucho.
        val stdout = Files.createTempFile("testigo-", ".out")
        val stderr = Files.createTempFile("testigo-", ".err")
        try {
            val process = try {
                ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
            } catch (e: IOException) {
                throw TestigoException("No se pudo ejecutar ${describe(command)}: ${e.message}")
            }
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw TestigoException("${describe(command)} no terminó en $timeout.")
            }
            return CommandResult(process.exitValue(), stdout.readText(), stderr.readText())
        } finally {
            stdout.deleteIfExists()
            stderr.deleteIfExists()
        }
    }

    /** Programa y subcomando, sin el resto de argumentos (pueden ser rutas largas). */
    private fun describe(command: List<String>): String {
        val program = command.first().substringAfterLast('/').substringAfterLast('\\')
        val subcommand = command.drop(1).firstOrNull { !it.startsWith("-") && !it.contains('/') }
        return listOfNotNull(program, subcommand).joinToString(" ")
    }
}
