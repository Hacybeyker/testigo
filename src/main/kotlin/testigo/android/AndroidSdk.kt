package testigo.android

import testigo.TestigoException
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Herramientas del Android SDK que usa el runner. [aapt2] es null si no hay build-tools instalados. */
data class AndroidSdk(val adb: Path, private val aapt2Path: Path?) {

    /** aapt2 del build-tools más nuevo, o null si no hay build-tools. */
    val aapt2OrNull: Path? get() = aapt2Path

    val aapt2: Path
        get() = aapt2Path ?: throw TestigoException(
            "No se encontró aapt2: instala Android SDK Build-Tools con testigo setup (o con el SDK Manager de Android Studio).",
        )

    companion object {
        /**
         * Busca el SDK en `ANDROID_HOME`, luego en `ANDROID_SDK_ROOT` y luego en la ruta por defecto de Android Studio.
         * adb también se acepta desde el PATH.
         */
        fun locate(
            env: Map<String, String> = System.getenv(),
            home: Path = Path.of(System.getProperty("user.home")),
            osName: String = System.getProperty("os.name"),
        ): AndroidSdk {
            val windows = osName.startsWith("Windows", ignoreCase = true)
            val exe = if (windows) ".exe" else ""
            val root = sequenceOf(env["ANDROID_HOME"], env["ANDROID_SDK_ROOT"])
                .filterNotNull()
                .map { Path.of(it) }
                // sequenceOf(...): un Path también es Iterable<Path>, y `plus(path)` agregaría sus partes.
                .plus(sequenceOf(defaultRoot(home, env, osName)))
                .firstOrNull { it.isDirectory() }

            val adb = root?.resolve("platform-tools/adb$exe")?.takeIf { it.isRegularFile() }
                ?: fromPath("adb$exe", env)
                ?: throw TestigoException(
                    "No se encontró adb: instálalo con testigo setup, o define ANDROID_HOME si el Android SDK está en otra carpeta.",
                )
            val aapt2 = root?.resolve("build-tools")?.let { latestAapt2(it, exe) }
            return AndroidSdk(adb, aapt2)
        }

        internal fun latestAapt2(buildTools: Path, exe: String = ""): Path? {
            if (!buildTools.isDirectory()) return null
            return buildTools.listDirectoryEntries()
                .map { it.resolve("aapt2$exe") }
                .filter { it.isRegularFile() }
                .maxWithOrNull(compareBy(VersionOrder) { it.parent.name })
        }

        /** Donde Android Studio deja el SDK, y donde lo instala `testigo setup`. */
        fun defaultRoot(home: Path, env: Map<String, String>, osName: String): Path = when {
            osName.startsWith("Mac", ignoreCase = true) -> home.resolve("Library/Android/sdk")
            osName.startsWith("Windows", ignoreCase = true) ->
                env["LOCALAPPDATA"]?.let { Path.of(it, "Android", "Sdk") } ?: home.resolve("AppData/Local/Android/Sdk")
            else -> home.resolve("Android/Sdk")
        }

        private fun fromPath(name: String, env: Map<String, String>): Path? =
            env["PATH"].orEmpty()
                .split(java.io.File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .map { Path.of(it, name) }
                .firstOrNull { it.isRegularFile() }
    }
}

/** Ordena versiones como `35.0.0`, `36.1.0` o `36.0.0-rc1` por sus números. */
internal object VersionOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val left = numbers(a)
        val right = numbers(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun numbers(version: String): List<Int> =
        version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}
