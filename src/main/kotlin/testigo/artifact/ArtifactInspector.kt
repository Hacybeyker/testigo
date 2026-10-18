package testigo.artifact

import testigo.TestigoException
import testigo.process.CommandRunner
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream
import kotlin.time.Duration.Companion.minutes

/** Lo que el runner necesita saber del APK o AAB antes de instalarlo. */
data class ArtifactInfo(
    val artifact: Artifact,
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val debuggable: Boolean,
    /** Identifica el build: los builds debug suelen repetir versionCode, así que se compara el hash. */
    val sha256: String,
) {
    /** `com.empresa.app · 7.0.0-qa (70) · APK` */
    val label: String
        get() = buildString {
            append(packageName)
            if (versionName != null || versionCode != null) {
                append(" · ").append(versionName ?: "?")
                versionCode?.let { append(" ($it)") }
            }
            append(" · ").append(artifact.format.label)
        }
}

/** Lee package, versión y si es debuggable: con `aapt2 dump badging` (APK) o `bundletool dump manifest` (AAB). */
class ArtifactInspector(
    private val runner: CommandRunner,
    private val aapt2: () -> Path,
    private val bundletool: Bundletool,
) {
    fun inspect(artifact: Artifact): ArtifactInfo {
        val manifest = when (artifact.format) {
            ArtifactFormat.APK -> {
                val result = runner.run(listOf(aapt2().toString(), "dump", "badging", artifact.path.toString()), 2.minutes)
                if (!result.ok) throw TestigoException("aapt2 no pudo leer el APK: ${result.output.lines().firstOrNull().orEmpty()}")
                ManifestParsers.badging(result.stdout)
            }
            ArtifactFormat.AAB -> {
                val result = bundletool.run(listOf("dump", "manifest", "--bundle=${artifact.path}"), 2.minutes)
                if (!result.ok) throw TestigoException("bundletool no pudo leer el AAB: ${result.output.lines().firstOrNull().orEmpty()}")
                ManifestParsers.manifestXml(result.stdout)
            }
        } ?: throw TestigoException("No se encontró el package en el manifest de ${artifact.path.fileName}.")

        return ArtifactInfo(
            artifact = artifact,
            packageName = manifest.packageName,
            versionCode = manifest.versionCode,
            versionName = manifest.versionName,
            debuggable = manifest.debuggable,
            sha256 = sha256(artifact.path),
        )
    }

    companion object {
        fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            path.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal data class ManifestInfo(
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val debuggable: Boolean,
)

internal object ManifestParsers {
    /** `package: name='com.x' versionCode='7' versionName='7.0'` y la línea `application-debuggable`. */
    fun badging(output: String): ManifestInfo? {
        val line = output.lines().firstOrNull { it.startsWith("package:") } ?: return null
        fun attr(name: String) = Regex("\\b$name='([^']*)'").find(line)?.groupValues?.get(1)
        val packageName = attr("name")?.takeIf { it.isNotEmpty() } ?: return null
        return ManifestInfo(
            packageName = packageName,
            versionCode = attr("versionCode")?.toLongOrNull(),
            versionName = attr("versionName")?.takeIf { it.isNotEmpty() },
            debuggable = output.lines().any { it.trim() == "application-debuggable" },
        )
    }

    /** El AndroidManifest.xml que imprime `bundletool dump manifest`. */
    fun manifestXml(xml: String): ManifestInfo? {
        fun attr(name: String) = Regex("\\s$name=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)
        val packageName = attr("package")?.takeIf { it.isNotEmpty() } ?: return null
        return ManifestInfo(
            packageName = packageName,
            versionCode = attr("android:versionCode")?.toLongOrNull(),
            versionName = attr("android:versionName")?.takeIf { it.isNotEmpty() },
            debuggable = attr("android:debuggable") == "true",
        )
    }
}
