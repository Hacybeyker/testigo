package testigo.setup

/**
 * Maestro se descarga del release oficial de GitHub (el mismo zip que usa su instalador). Las versiones de [PINNED]
 * tienen el SHA-256 fijado aquí, verificado contra el que muestra GitHub; para otra versión se usa el
 * `checksums_sha256.txt` que publica el mismo release.
 */
object MaestroRelease {
    const val DEFAULT_VERSION = "2.11.0"
    const val SIZE = "315 MB"

    private val PINNED = mapOf(
        "2.11.0" to "5384593cb4e7a106489e75a821d157dd43f4e438df6bc308b72e82c685e1283a",
    )

    fun zipUrl(version: String) = "https://github.com/mobile-dev-inc/maestro/releases/download/cli-$version/maestro.zip"

    fun checksumsUrl(version: String) = "https://github.com/mobile-dev-inc/maestro/releases/download/cli-$version/checksums_sha256.txt"

    fun pinnedSha256(version: String): String? = PINNED[version]

    /** La línea `<sha256>  maestro.zip` de `checksums_sha256.txt`. */
    fun sha256From(checksums: String): String? =
        checksums.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == "maestro.zip" }
            ?.first()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.lowercase()

    fun isVersion(value: String): Boolean = value.matches(Regex("\\d+\\.\\d+\\.\\d+"))
}

/**
 * Las command-line tools oficiales del Android SDK (traen `sdkmanager`), con el SHA-256 que publica
 * developer.android.com/studio. Con ellas se instala lo único que usa testigo: platform-tools (`adb`) y build-tools (`aapt2`).
 */
object CommandLineTools {
    const val BUILD = "15859902"
    const val BUILD_TOOLS = "36.0.0"
    const val PLATFORM_TOOLS_PACKAGE = "platform-tools"
    const val BUILD_TOOLS_PACKAGE = "build-tools;$BUILD_TOOLS"

    private const val BASE = "https://dl.google.com/android/repository/"

    /** null en un sistema sin descarga (Windows por ahora). */
    fun download(osName: String, arch: String): Download? = when {
        osName.startsWith("Mac", ignoreCase = true) && arch in setOf("aarch64", "arm64") ->
            Download("${BASE}commandlinetools-mac_arm64-${BUILD}_latest.zip", "835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e", "156 MB")
        osName.startsWith("Mac", ignoreCase = true) ->
            Download("${BASE}commandlinetools-mac_x86_64-${BUILD}_latest.zip", "c5a6378ab5cf7e0d5701921405115befff13e9ff7417fb588389338f8bd050f3", "156 MB")
        osName.startsWith("Linux", ignoreCase = true) ->
            Download("${BASE}commandlinetools-linux-${BUILD}_latest.zip", "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583", "182 MB")
        else -> null
    }
}
