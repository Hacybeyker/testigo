package testigo.setup

import testigo.TestigoException
import testigo.process.CommandResult
import testigo.process.CommandRunner
import testigo.process.ProcessCommandRunner
import testigo.util.sha256
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.moveTo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Los procesos de `testigo setup`. [interactive] comparte la terminal: el avance de curl y la licencia de sdkmanager
 * se ven y se responden ahí. Una interfaz para poder probar sin descargar nada.
 */
interface SetupShell {
    /** Devuelve el código de salida. */
    fun interactive(command: List<String>, env: Map<String, String> = emptyMap()): Int

    fun capture(command: List<String>, timeout: Duration = 2.minutes): CommandResult
}

class ProcessSetupShell(private val runner: CommandRunner = ProcessCommandRunner()) : SetupShell {
    override fun interactive(command: List<String>, env: Map<String, String>): Int {
        val process = try {
            ProcessBuilder(command).inheritIO().also { it.environment().putAll(env) }.start()
        } catch (e: IOException) {
            throw TestigoException("No se pudo ejecutar ${command.first().substringAfterLast('/')}: ${e.message}")
        }
        return process.waitFor()
    }

    override fun capture(command: List<String>, timeout: Duration): CommandResult = runner.run(command, timeout)
}

/** Un archivo para descargar, con el SHA-256 que tiene que tener. */
data class Download(val url: String, val sha256: String, val size: String) {
    val fileName: String get() = url.substringAfterLast('/')
}

/** Descargas de `testigo setup`. Una interfaz para poder probar sin red. */
interface Fetcher {
    /** Deja [download] en [target] solo si su SHA-256 coincide. */
    fun fetch(download: Download, target: Path)

    /** Un archivo de texto chico, como la lista de SHA-256 de un release. */
    fun text(url: String): String
}

/**
 * Descarga con curl, que respeta el proxy de la terminal (`HTTPS_PROXY`), solo por HTTPS. El archivo se verifica
 * contra su SHA-256 antes de entregarlo: si no coincide, se borra sin usarlo.
 */
class CurlFetcher(private val shell: SetupShell) : Fetcher {

    override fun fetch(download: Download, target: Path) {
        target.parent.createDirectories()
        val partial = target.resolveSibling(target.fileName.toString() + ".part")
        val code = shell.interactive(listOf("curl", "-fL", "--progress-bar", "--proto", "=https", "--tlsv1.2", "-o", partial.toString(), download.url))
        if (code != 0) {
            partial.deleteIfExists()
            throw TestigoException(
                "No pude descargar ${download.url} (curl terminó con código $code). Revisa la conexión o el proxy: curl usa HTTPS_PROXY.",
            )
        }
        val actual = partial.sha256()
        if (!actual.equals(download.sha256, ignoreCase = true)) {
            partial.deleteIfExists()
            throw TestigoException("El SHA-256 de ${download.fileName} no coincide: lo borré sin usarlo (llegó $actual, se esperaba ${download.sha256}).")
        }
        partial.moveTo(target, overwrite = true)
    }

    override fun text(url: String): String {
        val result = shell.capture(listOf("curl", "-fsSL", "--proto", "=https", "--tlsv1.2", url))
        if (!result.ok) throw TestigoException("No pude descargar $url: ${result.output.lines().firstOrNull().orEmpty()}")
        return result.stdout
    }
}

/**
 * Descomprime un zip con el sistema de archivos zip de Java, que conserva los permisos de cada archivo (los
 * ejecutables de `bin/` siguen siendo ejecutables). Rechaza rutas que salgan de [target].
 */
object Unzip {
    fun extract(zip: Path, target: Path) {
        val base = target.toAbsolutePath().normalize().createDirectories()
        FileSystems.newFileSystem(zip, mapOf("enablePosixFileAttributes" to "true")).use { fs ->
            val root = fs.getPath("/")
            Files.walk(root).use { entries ->
                entries.forEach { entry ->
                    val relative = root.relativize(entry).toString()
                    if (relative.isEmpty()) return@forEach
                    val out = base.resolve(relative).normalize()
                    if (!out.startsWith(base) || out == base) throw TestigoException("${zip.fileName} trae una ruta fuera de su carpeta: $relative")
                    if (Files.isDirectory(entry)) {
                        out.createDirectories()
                    } else {
                        out.parent.createDirectories()
                        Files.copy(entry, out, StandardCopyOption.REPLACE_EXISTING)
                        copyPermissions(entry, out)
                    }
                }
            }
        }
    }

    private fun copyPermissions(entry: Path, out: Path) {
        runCatching {
            val permissions = Files.getPosixFilePermissions(entry).toMutableSet()
            permissions += PosixFilePermission.OWNER_READ
            permissions += PosixFilePermission.OWNER_WRITE
            Files.setPosixFilePermissions(out, permissions)
        }
    }
}
