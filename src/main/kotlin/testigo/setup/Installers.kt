package testigo.setup

import testigo.TestigoException
import testigo.process.Cleanup
import testigo.util.deleteTree
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.setPosixFilePermissions
import kotlin.time.Duration.Companion.minutes

/** Una carpeta de trabajo para una descarga: se borra al terminar, también si la corrida se corta con Ctrl+C. */
internal inline fun <T> withWorkDir(parent: Path, block: (Path) -> T): T {
    parent.createDirectories()
    val work = Files.createTempDirectory(parent, ".testigo-download-")
    Cleanup.register(work) { work.deleteTree() }
    try {
        return block(work)
    } finally {
        runCatching { work.deleteTree() }
        Cleanup.unregister(work)
    }
}

/**
 * Instala Maestro como lo hace su instalador oficial: `bin/` y `lib/` del zip quedan en [dir] (por defecto
 * `~/.maestro`). Sin root y sin tocar el perfil de la terminal: de eso se encarga [ShellEnv].
 */
class MaestroInstaller(private val downloader: Fetcher, private val progress: (String) -> Unit = {}) {

    /** El SHA-256 de maestro.zip para [version]: el fijado en testigo o el que publica el release. */
    fun sha256(version: String): String =
        MaestroRelease.pinnedSha256(version)
            ?: MaestroRelease.sha256From(downloader.text(MaestroRelease.checksumsUrl(version)))
            ?: throw TestigoException("El release cli-$version de Maestro no publica el SHA-256 de maestro.zip: no lo instalo.")

    /** Devuelve el ejecutable instalado. */
    fun install(version: String, dir: Path): Path {
        val download = Download(MaestroRelease.zipUrl(version), sha256(version), MaestroRelease.SIZE)
        return withWorkDir(dir) { work ->
            progress("Descargando ${download.url}")
            val zip = work.resolve("maestro.zip")
            downloader.fetch(download, zip)
            progress("SHA-256 verificado. Descomprimiendo…")
            val extracted = work.resolve("zip")
            Unzip.extract(zip, extracted)
            val source = extracted.resolve("maestro")
            if (!source.resolve("bin/maestro").isRegularFile()) throw TestigoException("maestro.zip no trae maestro/bin/maestro: no instalé nada.")
            // Igual que el instalador oficial (cp -rf maestro/* ~/.maestro), pero sin dejar jars de otra versión en lib/.
            source.listDirectoryEntries().forEach { child ->
                val destination = dir.resolve(child.name)
                destination.deleteTree()
                Files.move(child, destination)
            }
            dir.resolve("bin/maestro")
        }
    }
}

/**
 * Lo mínimo del Android SDK que usa testigo, con las herramientas oficiales de Google: si falta `sdkmanager`, descarga
 * las command-line tools en `<sdk>/cmdline-tools/latest`; luego `sdkmanager` instala [packages] y, antes, muestra
 * la licencia del SDK para que el usuario la acepte (testigo nunca la acepta por él).
 */
class AndroidSdkInstaller(
    private val downloader: Fetcher,
    private val shell: SetupShell,
    private val progress: (String) -> Unit = {},
) {
    fun install(root: Path, packages: List<String>, cmdlineTools: Download?, javaHome: Path) {
        val created = !root.exists()
        val sdkmanager = sdkmanager(root) ?: try {
            val download = cmdlineTools ?: throw TestigoException("No hay command-line tools del Android SDK para este sistema: instálalo con Android Studio.")
            installCommandLineTools(root, download)
        } catch (e: TestigoException) {
            // No dejar una carpeta del SDK vacía que parezca un SDK instalado.
            if (created) runCatching { if (root.isDirectory() && root.listDirectoryEntries().isEmpty()) root.deleteTree() }
            throw e
        }
        if (packages.isEmpty()) return
        progress("sdkmanager instala ${packages.joinToString(" y ")} en $root")
        val code = shell.interactive(listOf(sdkmanager.toString(), "--sdk_root=$root") + packages, mapOf("JAVA_HOME" to javaHome.toString()))
        if (code != 0) {
            throw TestigoException(
                "sdkmanager terminó con código $code. Si no aceptaste la licencia, vuelve a ejecutar testigo setup y acéptala: " +
                    "sin ella sdkmanager no instala nada.",
            )
        }
    }

    private fun installCommandLineTools(root: Path, download: Download): Path = withWorkDir(root) { work ->
        progress("Descargando ${download.url}")
        val zip = work.resolve(download.fileName)
        downloader.fetch(download, zip)
        progress("SHA-256 verificado. Descomprimiendo…")
        val extracted = work.resolve("zip")
        Unzip.extract(zip, extracted)
        val bin = Files.walk(extracted, 4).use { paths ->
            paths.filter { it.name == "sdkmanager" && it.parent?.name == "bin" && it.isRegularFile() }.findFirst().orElse(null)
        } ?: throw TestigoException("${download.fileName} no trae sdkmanager: no instalé nada.")
        val tools = bin.parent.parent
        val latest = root.resolve("cmdline-tools/latest")
        latest.deleteTree()
        latest.parent.createDirectories()
        Files.move(tools, latest)
        latest.resolve("bin/sdkmanager")
    }

    companion object {
        /** `cmdline-tools/latest/bin/sdkmanager` o el de la versión más nueva de `cmdline-tools/`. */
        fun sdkmanager(root: Path, windows: Boolean = false): Path? {
            val name = if (windows) "sdkmanager.bat" else "sdkmanager"
            val dir = root.resolve("cmdline-tools")
            if (!dir.isDirectory()) return null
            dir.resolve("latest/bin/$name").takeIf { it.isRegularFile() }?.let { return it }
            return dir.listDirectoryEntries()
                .map { it.resolve("bin/$name") }
                .filter { it.isRegularFile() }
                .maxByOrNull { it.parent.parent.name }
        }
    }
}

/**
 * La debug key de Android en `~/.android/debug.keystore`, igual a la que crea Android Studio la primera vez que
 * compila: bundletool firma con ella los APKs que genera desde un AAB. Es local: no descarga nada.
 */
class DebugKeystoreCreator(private val shell: SetupShell) {
    fun create(file: Path, javaHome: Path) {
        file.parent.createDirectories()
        val keytool = javaHome.resolve("bin/keytool")
        val result = shell.capture(
            listOf(
                keytool.toString(), "-genkeypair", "-keystore", file.toString(), "-alias", "androiddebugkey",
                "-storepass", "android", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US",
            ),
            1.minutes,
        )
        if (!result.ok || !file.isRegularFile()) throw TestigoException("keytool no pudo crear $file: ${result.output.lines().firstOrNull().orEmpty()}")
        // Solo para el usuario, como cualquier keystore.
        runCatching { file.setPosixFilePermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }
}
