package testigo.maestro

import testigo.TestigoException
import testigo.policy.MaestroProcess
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Una invocación de `maestro test` para un solo flow. */
data class MaestroTest(
    val flow: Path,
    val device: String,
    val env: Map<String, String>,
    /** Reporte HTML detallado del flow. */
    val report: Path,
    /** `--test-output-dir`: logs, comandos y screenshots de Maestro. */
    val outputDir: Path,
    /** Salida de consola de Maestro. */
    val console: Path,
    /** El driver se instala en el primer flow de la corrida; los siguientes lo reutilizan. */
    val reinstallDriver: Boolean,
    val timeout: Duration,
) {
    override fun toString(): String = "MaestroTest(flow=$flow, device=$device, envKeys=${env.keys})"
}

data class MaestroOutcome(val exitCode: Int, val timedOut: Boolean)

/** El CLI de Maestro. Siempre se lanza con [MaestroProcess]: entorno local-only y solo `test` o `--version`. */
class MaestroCli(val executable: Path) {

    fun test(test: MaestroTest): MaestroOutcome {
        val args = buildList {
            add("test")
            add("--device")
            add(test.device)
            add("--format")
            add("HTML-DETAILED")
            add("--output")
            add(test.report.toString())
            add("--test-output-dir")
            add(test.outputDir.toString())
            if (!test.reinstallDriver) add("--no-reinstall-driver")
            test.env.forEach { (key, value) ->
                add("-e")
                add("$key=$value")
            }
            add(test.flow.toString())
        }
        val process = MaestroProcess.builder(executable.toString(), args)
            .directory(test.flow.parent.toFile())
            .redirectErrorStream(true)
            .redirectOutput(test.console.toFile())
            .start()
        if (!process.waitFor(test.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            return MaestroOutcome(exitCode = -1, timedOut = true)
        }
        return MaestroOutcome(process.exitValue(), timedOut = false)
    }

    /** Versión instalada, por ejemplo `2.11.0`; null si no se pudo leer. */
    fun version(timeout: Duration = 1.minutes): String? {
        val output = Files.createTempFile("testigo-maestro-", ".txt")
        try {
            val process = MaestroProcess.builder(executable.toString(), listOf("--version"))
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return null
            }
            return Regex("\\d+\\.\\d+\\.\\d+").findAll(output.readText()).lastOrNull()?.value
        } finally {
            output.deleteIfExists()
        }
    }

    companion object {
        /**
         * Busca `maestro` en el PATH y, si no, donde lo dejan `testigo setup` y el instalador oficial: `MAESTRO_DIR/bin`
         * o `~/.maestro/bin`.
         */
        fun locate(
            env: Map<String, String> = System.getenv(),
            home: Path = Path.of(System.getProperty("user.home")),
            osName: String = System.getProperty("os.name"),
        ): MaestroCli {
            val names = if (osName.startsWith("Windows", ignoreCase = true)) {
                listOf("maestro.bat", "maestro.cmd", "maestro")
            } else {
                listOf("maestro")
            }
            // listOf(...): un Path también es Iterable<Path>, y `lista + path` agregaría sus partes, no la ruta.
            val dirs = env["PATH"].orEmpty().split(java.io.File.pathSeparatorChar).filter { it.isNotBlank() }.map { Path.of(it) } +
                listOfNotNull(env["MAESTRO_DIR"]?.takeIf { it.isNotBlank() }?.let { Path.of(it, "bin") }, home.resolve(".maestro/bin"))
            val executable = dirs.asSequence()
                .flatMap { dir -> names.asSequence().map { dir.resolve(it) } }
                .firstOrNull { it.isRegularFile() }
                ?: throw TestigoException(
                    "No se encontró maestro: instálalo con testigo setup. testigo doctor revisa el resto.",
                )
            return MaestroCli(executable)
        }

        /** Los flows duran menos de 3 minutos; esto cubre además el arranque del driver. */
        val DEFAULT_TIMEOUT: Duration = 15.minutes
    }
}
