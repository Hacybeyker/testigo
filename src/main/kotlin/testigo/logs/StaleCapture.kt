package testigo.logs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * Lo que una corrida anota de su captura de logcat mientras dura: de quién es ([ownerPid]), su `adb logcat` y el tamaño
 * que tenía cada buffer antes de agrandarlo ([buffers], vacío si no se cambió). Si la corrida muere sin limpiar (Ctrl+C,
 * `kill -9`), la siguiente lo lee y deja el dispositivo como estaba.
 */
internal data class CaptureRecord(
    val ownerPid: Long,
    val logcatPid: Long? = null,
    val buffers: Map<String, String> = emptyMap(),
) {
    fun encode(): String = buildString {
        appendLine("owner=$ownerPid")
        logcatPid?.let { appendLine("logcat=$it") }
        buffers.forEach { (name, size) -> appendLine("buffer.$name=$size") }
    }

    companion object {
        fun decode(lines: List<String>): CaptureRecord? {
            val values = lines.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            val owner = values.firstOrNull { it.first == "owner" }?.second?.toLongOrNull() ?: return null
            return CaptureRecord(
                ownerPid = owner,
                logcatPid = values.firstOrNull { it.first == "logcat" }?.second?.toLongOrNull(),
                buffers = values.filter { it.first.startsWith("buffer.") }.associate { it.first.removePrefix("buffer.") to it.second },
            )
        }
    }
}

/**
 * La carpeta donde las corridas anotan su captura de un dispositivo: un archivo por corrida (`<pid del dueño>.capture`).
 * Cada corrida solo escribe y borra el suyo; así dos corridas sobre el mismo dispositivo no se pisan.
 */
internal class CaptureStore(private val dir: Path) {
    fun file(ownerPid: Long): Path = dir.resolve("$ownerPid.capture")

    /** Escribe de una vez: otra corrida que lo lea a medias vería un archivo ilegible y lo tomaría por basura. */
    fun write(file: Path, record: CaptureRecord) {
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, ".capture-", ".tmp")
        try {
            temp.writeText(record.encode())
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.deleteIfExists()
        }
    }

    fun delete(file: Path) {
        runCatching { file.deleteIfExists() }
    }

    /** Cada captura anotada con su registro; null si el archivo no se entiende. */
    fun entries(): List<Pair<Path, CaptureRecord?>> {
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching { dir.listDirectoryEntries().filter { it.extension == "capture" }.sorted() }.getOrDefault(emptyList())
            .map { file -> file to runCatching { CaptureRecord.decode(file.readLines()) }.getOrNull() }
    }

    /** Alguna corrida que no es [except] y sigue viva. */
    fun othersAlive(except: Path): Boolean = entries().any { (file, record) -> file != except && record != null && Owner.alive(record) }

    /** Los tamaños originales que anotó una corrida viva: mientras sigue, lo que dice `logcat -g` ya no es lo original. */
    fun liveBuffers(): Map<String, String> =
        entries().firstNotNullOfOrNull { (_, record) -> record?.takeIf { Owner.alive(it) && it.buffers.isNotEmpty() }?.buffers }.orEmpty()
}

internal object Owner {
    fun alive(record: CaptureRecord): Boolean = ProcessHandle.of(record.ownerPid).map { it.isAlive }.orElse(false)
}

/**
 * Capturas de logcat que una corrida anterior dejó abiertas. Cada corrida anota la suya en el [CaptureStore]; la siguiente,
 * por cada anotación cuyo dueño ya no existe, cierra el `adb logcat` que quedó, devuelve los buffers a su tamaño y borra la
 * anotación. Si el dueño sigue vivo es otra corrida en marcha sobre el mismo dispositivo: no se toca.
 */
internal object StaleCapture {
    /**
     * Deja el dispositivo como estaba y devuelve qué hizo, una nota por cada `adb logcat` que cerró. [restore] devuelve los
     * buffers a su tamaño y dice si adb contestó: si no, la anotación se queda para el próximo intento. Si hay una corrida
     * viva no se tocan los buffers: ella los sigue necesitando y los devuelve al terminar.
     */
    fun close(store: CaptureStore, restore: (Map<String, String>) -> Boolean): List<String> {
        val entries = store.entries()
        val running = entries.any { (_, record) -> record != null && Owner.alive(record) }
        val notes = mutableListOf<String>()
        for ((file, record) in entries) {
            if (record != null && Owner.alive(record)) continue
            if (record != null) {
                closeLogcat(record)?.let { notes += it }
                if (record.buffers.isNotEmpty() && !running && !restore(record.buffers)) continue
            }
            store.delete(file)
        }
        return notes
    }

    private fun closeLogcat(record: CaptureRecord): String? {
        val logcatPid = record.logcatPid ?: return null
        val handle = ProcessHandle.of(logcatPid).orElse(null)?.takeIf { it.isAlive && isLogcat(it.info()) } ?: return null
        handle.destroy()
        if (runCatching { handle.onExit().get(3, TimeUnit.SECONDS) }.isFailure) handle.destroyForcibly()
        return "cerré una captura de logcat de una corrida anterior que seguía abierta (pid $logcatPid)"
    }

    /** Solo un `adb logcat`: el pid pudo pasar a otro proceso desde entonces. */
    private fun isLogcat(info: ProcessHandle.Info): Boolean {
        val line = info.commandLine().orElse(null)
        if (line != null) return "logcat" in line
        return info.command().map { it.substringAfterLast('/').removeSuffix(".exe") == "adb" }.orElse(false)
    }
}
