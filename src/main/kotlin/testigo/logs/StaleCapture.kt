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
 *
 * Un pid solo no identifica un proceso: el sistema lo reusa. Por eso se anota también cuándo arrancó cada uno
 * ([ownerStart] y [logcatStart], en milisegundos de época; null si el sistema no lo dice).
 */
internal data class CaptureRecord(
    val ownerPid: Long,
    val ownerStart: Long? = null,
    val logcatPid: Long? = null,
    val logcatStart: Long? = null,
    val buffers: Map<String, String> = emptyMap(),
) {
    fun encode(): String = buildString {
        appendLine("owner=$ownerPid")
        ownerStart?.let { appendLine("ownerStart=$it") }
        logcatPid?.let { appendLine("logcat=$it") }
        logcatStart?.let { appendLine("logcatStart=$it") }
        buffers.forEach { (name, size) -> appendLine("buffer.$name=$size") }
    }

    companion object {
        fun decode(lines: List<String>): CaptureRecord? {
            val values = lines.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            val owner = values.firstOrNull { it.first == "owner" }?.second?.toLongOrNull() ?: return null
            fun long(key: String) = values.firstOrNull { it.first == key }?.second?.toLongOrNull()
            return CaptureRecord(
                ownerPid = owner,
                ownerStart = long("ownerStart"),
                logcatPid = long("logcat"),
                logcatStart = long("logcatStart"),
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
    /** El dueño sigue vivo. Si su pid lo tiene ahora otro proceso (arrancó en otro momento), el dueño ya no existe. */
    fun alive(record: CaptureRecord): Boolean {
        val handle = ProcessHandle.of(record.ownerPid).orElse(null)?.takeIf { it.isAlive } ?: return false
        val recorded = record.ownerStart
        val actual = Identity.startOf(handle)
        // Si el sistema no dice cuándo arrancó, no se puede saber que el pid cambió de dueño: se supone el mismo.
        return recorded == null || actual == null || Identity.sameStart(recorded, actual)
    }
}

/** Qué se sabe de un proceso vivo para reconocerlo: cuándo arrancó, su ejecutable y sus argumentos (null si el sistema no los dice). */
internal data class ProcessFacts(val startMillis: Long?, val executable: String?, val arguments: List<String>?)

/** Reconocer un proceso por algo más que su pid. */
internal object Identity {
    /**
     * Dos medidas del arranque del mismo proceso pueden diferir por un segundo: en Linux sale del `btime` del kernel, que se
     * ajusta con el reloj. Un pid reusado arranca después de que el anterior terminó, mucho más tarde que esto.
     */
    const val START_TOLERANCE_MS = 2_000L

    fun startOf(handle: ProcessHandle): Long? = handle.info().startInstant().map { it.toEpochMilli() }.orElse(null)

    fun sameStart(recorded: Long, actual: Long): Boolean = kotlin.math.abs(recorded - actual) <= START_TOLERANCE_MS

    fun facts(handle: ProcessHandle): ProcessFacts {
        val info = handle.info()
        return ProcessFacts(startOf(handle), info.command().orElse(null), info.arguments().map { it.toList() }.orElse(null))
    }

    /**
     * Es el `adb -s <serial> logcat` que lanzó la corrida que lo anotó ([recordedStart]). Exige que haya arrancado en ese
     * momento y que, si el sistema dice sus argumentos, sean los de un logcat de este dispositivo: así un pid reusado por el
     * `adb logcat` de otro dispositivo, `pidcat`, `tail -f logcat.txt` o el servidor de adb no se cierra. Sin saber cuándo
     * arrancó, no se cierra: mejor dejar un logcat abierto que matar un proceso ajeno.
     */
    fun isLogcat(facts: ProcessFacts, recordedStart: Long?, serial: String): Boolean {
        if (recordedStart == null || facts.startMillis == null || !sameStart(recordedStart, facts.startMillis)) return false
        val args = facts.arguments?.takeIf { it.isNotEmpty() }
        if (args != null) return "logcat" in args && args.zipWithNext().any { (flag, value) -> flag == "-s" && value == serial }
        // Windows no dice los argumentos de otros procesos: queda el ejecutable, y el momento de arranque lo hace único.
        return facts.executable?.substringAfterLast('/')?.substringAfterLast('\\')?.removeSuffix(".exe") == "adb"
    }
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
    fun close(store: CaptureStore, serial: String, restore: (Map<String, String>) -> Boolean): List<String> {
        val entries = store.entries()
        val running = entries.any { (_, record) -> record != null && Owner.alive(record) }
        val notes = mutableListOf<String>()
        for ((file, record) in entries) {
            if (record != null && Owner.alive(record)) continue
            if (record != null) {
                closeLogcat(record, serial)?.let { notes += it }
                if (record.buffers.isNotEmpty() && !running && !restore(record.buffers)) continue
            }
            store.delete(file)
        }
        return notes
    }

    private fun closeLogcat(record: CaptureRecord, serial: String): String? {
        val logcatPid = record.logcatPid ?: return null
        val handle = ProcessHandle.of(logcatPid).orElse(null)?.takeIf { it.isAlive } ?: return null
        if (!Identity.isLogcat(Identity.facts(handle), record.logcatStart, serial)) return null
        handle.destroy()
        if (runCatching { handle.onExit().get(3, TimeUnit.SECONDS) }.isFailure) handle.destroyForcibly()
        return "cerré una captura de logcat de una corrida anterior que seguía abierta (pid $logcatPid)"
    }
}
