package testigo.artifact

import java.nio.file.Path

/** Formato del artefacto a probar. Un AAB se convierte localmente con bundletool (paso 3). */
enum class ArtifactFormat(val label: String) {
    APK("APK"),
    AAB("AAB"),
    ;

    companion object {
        fun of(fileName: String): ArtifactFormat? = when {
            fileName.endsWith(".apk", ignoreCase = true) -> APK
            fileName.endsWith(".aab", ignoreCase = true) -> AAB
            else -> null
        }
    }
}

data class Artifact(val path: Path, val format: ArtifactFormat)
