package testigo.doctor

import testigo.process.CommandRunner
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.seconds

/** Lo que dice `java -XshowSettings:properties -version` del Java que se va a usar. */
data class JavaSettings(val version: String?, val specification: String?, val home: String?) {
    /** `21` para Java 21, `8` para Java 1.8. */
    val major: Int? get() = specification?.let(JavaProbe::major)
}

/** Dónde está el `java` que usan Maestro y el runner, y de dónde salió. */
data class JavaCommand(val java: Path, val fromJavaHome: Boolean)

/**
 * El Java de Maestro y del runner. Los dos scripts de arranque (Gradle `application`) usan `JAVA_HOME/bin/java`
 * y, sin `JAVA_HOME`, el `java` del PATH. El runner además valida la versión en `bin/testigo` antes de arrancar.
 */
object JavaProbe {
    const val MIN_MAJOR = 17

    /** null si `JAVA_HOME` no tiene `bin/java`, o si no hay `JAVA_HOME` ni `java` en el PATH. */
    fun command(env: Map<String, String>, osName: String): JavaCommand? {
        val exe = if (osName.startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        val javaHome = env["JAVA_HOME"]?.takeIf { it.isNotBlank() }
        if (javaHome != null) {
            return Path.of(javaHome, "bin", exe).takeIf { it.isRegularFile() }?.let { JavaCommand(it, fromJavaHome = true) }
        }
        return env["PATH"].orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Path.of(it, exe) }
            .firstOrNull { it.isRegularFile() }
            ?.let { JavaCommand(it, fromJavaHome = false) }
    }

    /** null si ese `java` no arranca, por ejemplo el `/usr/bin/java` de macOS sin ningún JDK instalado. */
    fun settings(java: Path, runner: CommandRunner): JavaSettings? {
        val result = runner.run(listOf(java.toString(), "-XshowSettings:properties", "-version"), 30.seconds)
        if (!result.ok) return null
        return parse(result.output)
    }

    internal fun parse(output: String): JavaSettings? {
        fun property(name: String) = Regex("(?m)^\\s*${Regex.escape(name)} = (.+)$").find(output)?.groupValues?.get(1)?.trim()
        val settings = JavaSettings(property("java.version"), property("java.specification.version"), property("java.home"))
        return settings.takeIf { it.version != null || it.specification != null }
    }

    /** `1.8` → 8, `11` → 11, `21.0.8` → 21, `22-ea` → 22. */
    fun major(version: String): Int? {
        val trimmed = version.trim().removePrefix("1.")
        return trimmed.takeWhile { it.isDigit() }.toIntOrNull()
    }
}
