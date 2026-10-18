package testigo.setup

import testigo.process.CommandResult
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration

/** Un zip con permisos POSIX por archivo (`rwxr-xr-x`), como los de Maestro y las command-line tools. */
fun zipWith(target: Path, files: Map<String, Pair<String, String?>>): Path {
    target.parent.createDirectories()
    Files.deleteIfExists(target)
    val uri = URI.create("jar:" + target.toUri())
    FileSystems.newFileSystem(uri, mapOf("create" to "true", "enablePosixFileAttributes" to "true")).use { fs ->
        files.forEach { (name, file) ->
            val path = fs.getPath(name)
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, file.first)
            file.second?.let { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(it)) }
        }
    }
    return target
}

/** Descargas de mentira: cada URL devuelve un archivo preparado. No verifica el SHA-256 (eso se prueba en CurlFetcher). */
class FakeFetcher(private val files: Map<String, Path>, private val texts: Map<String, String> = emptyMap()) : Fetcher {
    val fetched = mutableListOf<Download>()

    override fun fetch(download: Download, target: Path) {
        fetched += download
        val source = files.entries.firstOrNull { download.url.endsWith(it.key) }?.value ?: error("URL inesperada: ${download.url}")
        target.parent.createDirectories()
        Files.copy(source, target)
    }

    override fun text(url: String): String = texts[url] ?: error("URL inesperada: $url")
}

/** Procesos de mentira: sdkmanager deja adb y aapt2 en el SDK; keytool crea el keystore. */
class FakeSetupShell(
    private val sdkmanagerCode: Int = 0,
    private val curl: (List<String>) -> Int = { 0 },
) : SetupShell {
    val interactive = mutableListOf<Pair<List<String>, Map<String, String>>>()
    val captured = mutableListOf<List<String>>()

    override fun interactive(command: List<String>, env: Map<String, String>): Int {
        interactive += command to env
        if (command.first() == "curl") return curl(command)
        if (command.first().endsWith("sdkmanager")) {
            if (sdkmanagerCode != 0) return sdkmanagerCode
            val root = Path.of(command.first { it.startsWith("--sdk_root=") }.removePrefix("--sdk_root="))
            if ("platform-tools" in command) root.resolve("platform-tools").createDirectories().resolve("adb").writeText("adb")
            command.firstOrNull { it.startsWith("build-tools;") }?.let {
                root.resolve("build-tools/${it.removePrefix("build-tools;")}").createDirectories().resolve("aapt2").writeText("aapt2")
            }
            return 0
        }
        error("comando inesperado: $command")
    }

    override fun capture(command: List<String>, timeout: Duration): CommandResult {
        captured += command
        if (command.first().endsWith("keytool")) {
            val file = Path.of(command[command.indexOf("-keystore") + 1])
            file.writeText("keystore")
            return CommandResult(0, "", "")
        }
        error("comando inesperado: $command")
    }
}
