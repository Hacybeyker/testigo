package testigo.artifact

import testigo.TestigoException
import testigo.process.CommandResult
import testigo.process.CommandRunner
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration

/**
 * bundletool oficial que viaja con el runner (ver `downloadBundletool` en build.gradle.kts).
 * Corre en un proceso aparte con el mismo Java que el runner: el jar trae su propia versión de Kotlin
 * y no puede compartir classpath con el CLI.
 */
class Bundletool(
    private val runner: CommandRunner,
    private val jar: () -> Path = { locate() },
    private val java: Path = Path.of(System.getProperty("java.home"), "bin", "java"),
) {
    fun run(args: List<String>, timeout: Duration): CommandResult =
        runner.run(listOf(java.toString(), "-jar", jar().toString()) + args, timeout)

    companion object {
        /**
         * Orden de búsqueda: `-Dtestigo.bundletool`, `TESTIGO_BUNDLETOOL` y, subiendo desde donde está el runner,
         * `tools/` (distribución instalada) o `.gradle/downloads/` (al ejecutar desde el proyecto o el IDE).
         */
        fun locate(
            property: String? = System.getProperty("testigo.bundletool"),
            env: String? = System.getenv("TESTIGO_BUNDLETOOL"),
            codeLocation: Path? = runnerLocation(),
        ): Path {
            listOfNotNull(property, env).map { Path.of(it) }.firstOrNull()?.let { return it }
            generateSequence(codeLocation) { it.parent }
                .take(6)
                .flatMap { sequenceOf(it.resolve("tools"), it.resolve(".gradle/downloads")) }
                .firstNotNullOfOrNull { dir -> jarIn(dir) }
                ?.let { return it }
            throw TestigoException("Falta bundletool en esta instalación del runner: vuelve a generarla con ./gradlew installDist.")
        }

        private fun jarIn(dir: Path): Path? =
            if (!dir.isDirectory()) {
                null
            } else {
                dir.listDirectoryEntries("bundletool-all-*.jar").maxByOrNull { it.name }
            }

        private fun runnerLocation(): Path? = runCatching {
            Path.of(Bundletool::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
    }
}
