package testigo.redact

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * `OK`: sanitizada y el leak scan no encontró nada. `NO_APTA`: el leak scan encontró datos sin enmascarar.
 * `SIN_SANITIZAR`: corrida sin `--redact`; trae tokens y datos tal cual.
 */
enum class EvidenceStatus { OK, NO_APTA, SIN_SANITIZAR }

/** Algo que quedó sin enmascarar. [file] es relativo a la carpeta del flow. Nunca incluye el valor. */
data class Leak(val file: Path, val line: Int, val rule: String)

data class LeakScan(
    val scanned: Int,
    val leaks: List<Leak>,
    /** Archivos binarios (video, capturas): no se pueden sanitizar ni revisar. */
    val unscanned: List<Path>,
)

/** Leak scan: vuelve a pasar todas las reglas sobre la evidencia ya escrita de un flow. */
object LeakScanner {

    /** Todo archivo de texto de [dir], salvo `raw/`, donde `--keep-raw` deja los crudos a propósito. */
    fun scan(dir: Path, redactor: Redactor): LeakScan {
        val files = Files.walk(dir).use { paths -> paths.filter { it.isRegularFile() }.toList() }
            .filterNot { dir.relativize(it).getName(0).toString() == RawEvidence.DIR }
            .sorted()
        val (text, binary) = files.partition(EvidenceFiles::isText)
        val leaks = text.flatMap { file ->
            redactor.leaks(EvidenceFiles.read(file)).map { Leak(dir.relativize(file), it.line, it.rule) }
        }
        return LeakScan(text.size, leaks, binary.map { dir.relativize(it) })
    }
}

object EvidenceFiles {
    private val TEXT = setOf("txt", "json", "jsonl", "har", "log", "html", "htm", "xml", "csv", "yaml", "yml", "md")
    private val BINARY = setOf("png", "jpg", "jpeg", "gif", "webp", "mp4", "webm", "mkv", "db", "db-wal", "db-shm", "zip", "gz")
    private const val SNIFF_BYTES = 8192

    /** Por extensión y, si no se conoce, por contenido: un byte 0 en el inicio es binario. */
    fun isText(file: Path): Boolean {
        val extension = file.extension.lowercase()
        if (extension in TEXT) return true
        if (extension in BINARY) return false
        val head = file.inputStream().use { it.readNBytes(SNIFF_BYTES) }
        return head.none { it == 0.toByte() }
    }

    /** UTF-8; si el archivo no lo es, byte a byte, para no perder nada que revisar. */
    fun read(file: Path): String = runCatching { file.readText() }.getOrElse { String(file.readBytes(), Charsets.ISO_8859_1) }
}

/** Datos crudos sin sanitizar. Con `--keep-raw` quedan en `<flow>/raw/` para depurar; si no, se borran. */
object RawEvidence {
    const val DIR = "raw"

    fun keepOrDelete(file: Path, flowDir: Path, name: String, keep: Boolean) {
        if (!file.exists()) return
        if (keep) {
            val target = flowDir.resolve(DIR).createDirectories().resolve(name)
            target.parent.createDirectories()
            file.moveTo(target, StandardCopyOption.REPLACE_EXISTING)
        } else {
            file.deleteIfExists()
        }
    }
}
