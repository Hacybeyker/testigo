package testigo.artifact

import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Si un APK o AAB trae Chucker: sus clases internas están en el dex. La variante `library-no-op` (la de release) no
 * las tiene, así que un build sin captura real no cuenta. Solo lee el archivo local.
 */
object ChuckerDetector {
    private val MARKER = "Lcom/chuckerteam/chucker/internal/".toByteArray()

    /** null si el archivo no se pudo leer como zip. */
    fun detect(artifact: Path): Boolean? = runCatching {
        ZipFile(artifact.toFile()).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".dex") }
                .any { entry -> zip.getInputStream(entry).use { contains(it.readBytes(), MARKER) } }
        }
    }.getOrNull()

    internal fun contains(data: ByteArray, marker: ByteArray): Boolean {
        if (marker.isEmpty() || data.size < marker.size) return false
        val first = marker[0]
        outer@ for (i in 0..data.size - marker.size) {
            if (data[i] != first) continue
            for (j in 1 until marker.size) if (data[i + j] != marker[j]) continue@outer
            return true
        }
        return false
    }
}
