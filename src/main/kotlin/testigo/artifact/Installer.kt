package testigo.artifact

import testigo.TestigoException
import testigo.android.Adb
import testigo.android.DeviceInfo
import testigo.process.Cleanup
import testigo.process.CommandResult
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

enum class InstallOutcome(val label: String) {
    UNCHANGED("sin cambios: ya estaba instalada"),
    INSTALLED("instalada"),
    UPDATED("actualizada"),
    REINSTALLED("reinstalada: la anterior tenía otra firma y se desinstaló"),
}

/** Firma para los APKs que se generan desde un AAB. Sin esto, bundletool usa la debug key (~/.android/debug.keystore). */
class AabSigning(val keystore: Path, val alias: String, private val password: String) {
    /** Identifica la firma en la caché de APKs, sin incluir la clave. */
    internal val id: String get() = "$keystore#$alias"

    /** La clave va en un archivo temporal privado, nunca en la línea de comandos. */
    internal fun writePasswordFile(): Path {
        val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
        val file = if (posix) {
            Files.createTempFile("testigo-ks-", ".txt", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createTempFile("testigo-ks-", ".txt")
        }
        file.writeText(password)
        return file
    }

    override fun toString(): String = "AabSigning(keystore=$keystore, alias=$alias)"
}

/**
 * Instala la app solo si hace falta. Un APK se instala con `adb install`; un AAB pasa por `bundletool build-apks`
 * (una vez por AAB, dispositivo y firma: el set de APKs queda en caché) y `bundletool install-apks`.
 */
class Installer(
    private val adb: Adb,
    private val bundletool: Bundletool,
    private val aapt2: () -> Path,
    private val cacheDir: Path,
    private val state: InstallState,
    private val signing: AabSigning?,
    private val confirm: (String) -> Boolean,
    private val progress: (String) -> Unit = {},
    /** Sin [signing], la debug key con la que bundletool firma (ver [DebugKeystore]). */
    private val debugKeystore: () -> Path? = { DebugKeystore.locate() },
) {
    fun ensureInstalled(device: DeviceInfo, app: ArtifactInfo): InstallOutcome {
        val serial = device.serial
        val packageName = app.packageName
        val before = adb.installedPackage(serial, packageName)
        val record = state.get(serial, packageName)
        if (before != null && record != null && record.sha256 == app.sha256 && record.lastUpdateTime == before.lastUpdateTime.orEmpty()) {
            return InstallOutcome.UNCHANGED
        }

        var outcome = if (before == null) InstallOutcome.INSTALLED else InstallOutcome.UPDATED
        var result = install(device, app)
        if (!succeeded(result) && SIGNATURE_MISMATCH in result.output) {
            val question = "La app instalada en $serial tiene otra firma. Para instalar esta hay que desinstalarla, " +
                "y eso borra sus datos. ¿Desinstalar $packageName?"
            if (!confirm(question)) {
                throw TestigoException(
                    "La app instalada en $serial tiene otra firma. Desinstálala (borra sus datos) y vuelve a ejecutar:\n" +
                        "  adb -s $serial uninstall $packageName",
                )
            }
            progress("desinstalando $packageName…")
            val uninstall = adb.uninstall(serial, packageName)
            if (!uninstall.ok) throw TestigoException("No se pudo desinstalar $packageName: ${uninstall.output}")
            result = install(device, app)
            outcome = InstallOutcome.REINSTALLED
        }
        if (!succeeded(result)) {
            throw TestigoException("No se pudo instalar ${app.artifact.path.fileName} en $serial: ${failureReason(result)}")
        }

        val after = adb.installedPackage(serial, packageName)
            ?: throw TestigoException("La instalación terminó, pero $packageName no aparece en $serial.")
        state.put(serial, packageName, InstallState.Record(app.sha256, after.lastUpdateTime.orEmpty()))
        return outcome
    }

    private fun install(device: DeviceInfo, app: ArtifactInfo): CommandResult = when (app.artifact.format) {
        ArtifactFormat.APK -> {
            progress("instalando ${app.artifact.path.fileName}…")
            adb.install(device.serial, app.artifact.path)
        }
        ArtifactFormat.AAB -> {
            val apks = apkSet(device, app)
            progress("instalando los APKs generados desde ${app.artifact.path.fileName}…")
            bundletool.run(
                listOf("install-apks", "--apks=$apks", "--device-id=${device.serial}", "--adb=${adb.path}", "--allow-downgrade"),
                5.minutes,
            )
        }
    }

    /** Set de APKs para este AAB, dispositivo y firma. Se genera una sola vez y queda en caché. */
    private fun apkSet(device: DeviceInfo, app: ArtifactInfo): Path {
        // Sin firma, bundletool genera APKs que Android no instala: se avisa antes y no queda nada en caché.
        val debugKey = if (signing == null) debugKeystore() ?: throw TestigoException(NO_SIGNING_KEY) else null
        val spec = cacheDir.resolve("device-specs").createDirectories().resolve("${safeName(device.serial)}.json")
        val specResult = bundletool.run(
            listOf("get-device-spec", "--device-id=${device.serial}", "--adb=${adb.path}", "--output=$spec", "--overwrite"),
            2.minutes,
        )
        if (!specResult.ok) {
            throw TestigoException("bundletool no pudo leer las características de ${device.serial}: ${failureReason(specResult)}")
        }

        // La firma entra en la clave: si cambia la debug key, se vuelven a generar los APKs.
        val signingId = signing?.id ?: "debug-key:${ArtifactInspector.sha256(debugKey!!)}"
        val key = sha256("${app.sha256}\n${spec.readText()}\n$signingId").take(32)
        val apks = cacheDir.resolve("apks").createDirectories().resolve("$key.apks")
        if (apks.exists()) return apks

        progress("generando los APKs desde ${app.artifact.path.fileName} (solo la primera vez para este dispositivo)…")
        val tmp = apks.resolveSibling("$key.tmp.apks")
        val passwordFile = signing?.writePasswordFile()
        // El archivo con la clave no debe quedar en /tmp si la corrida se corta.
        passwordFile?.let { file -> Cleanup.register(file) { file.deleteIfExists() } }
        try {
            val signingArgs = if (signing != null && passwordFile != null) {
                listOf(
                    "--ks=${signing.keystore}",
                    "--ks-key-alias=${signing.alias}",
                    "--ks-pass=file:$passwordFile",
                    "--key-pass=file:$passwordFile",
                )
            } else {
                emptyList()
            }
            val result = bundletool.run(
                listOf(
                    "build-apks",
                    "--bundle=${app.artifact.path}",
                    "--output=$tmp",
                    "--device-spec=$spec",
                    "--aapt2=${aapt2()}",
                    "--overwrite",
                ) + signingArgs,
                10.minutes,
            )
            if (!result.ok) throw TestigoException("bundletool no pudo generar los APKs: ${failureReason(result)}")
            if (DebugKeystore.UNSIGNED_WARNING in result.output) throw TestigoException(NO_SIGNING_KEY)
            Files.move(tmp, apks, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            passwordFile?.deleteIfExists()
            passwordFile?.let { Cleanup.unregister(it) }
            tmp.deleteIfExists()
        }
        return apks
    }

    companion object {
        private const val SIGNATURE_MISMATCH = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"

        internal val NO_SIGNING_KEY =
            "No hay con qué firmar los APKs que bundletool genera desde el AAB: testigo.yaml no tiene app.signing y no existe " +
                "~/.android/debug.keystore (Android Studio la crea al compilar por primera vez). Define app.signing o crea la debug key:\n" +
                "  ${DebugKeystore.CREATE_COMMAND}"

        /** adb antiguos devuelven 0 aunque fallen: también se revisa `Failure [`. */
        private fun succeeded(result: CommandResult) = result.ok && "Failure [" !in result.output

        private fun failureReason(result: CommandResult): String {
            val lines = result.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val reason = lines.firstOrNull { "Failure [" in it || "INSTALL_" in it || it.startsWith("Error") }
                ?: lines.lastOrNull()
                ?: "código ${result.exitCode}"
            return if ("NO_CERTIFICATES" in reason) {
                "$reason\n  Los APKs quedaron sin firma: no existe ~/.android/debug.keystore. Define app.signing en testigo.yaml."
            } else {
                reason
            }
        }

        private fun safeName(serial: String) = serial.replace(Regex("[^A-Za-z0-9._-]"), "_")

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
