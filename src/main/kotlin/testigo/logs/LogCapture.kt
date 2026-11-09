package testigo.logs

import testigo.process.Cleanup
import testigo.process.CommandRunner
import testigo.redact.EvidenceFiles
import testigo.redact.RawEvidence
import testigo.redact.Redactor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * Lo que quedó de logs de un flow. [crash] es el resumen del crash de la app, si hubo. [note] avisa de algo que el dev
 * debe saber de la captura, como una anterior que había quedado abierta.
 */
data class LogOutcome(val logcat: Path?, val crashFile: Path?, val crash: String?, val note: String? = null)

/** Captura de logs alrededor de un flow. Una interfaz para poder probar sin dispositivo. */
interface LogCollector {
    fun start(dir: Path)

    /** Todo lo que se escribe pasa antes por [redactor]. */
    fun finish(dir: Path, redactor: Redactor): LogOutcome

    /**
     * La corrida se cortó antes de [finish] (Maestro lanzó una excepción): deja el dispositivo como estaba y borra lo que
     * escribió fuera de `evidence/`. No escribe evidencia y no falla.
     */
    fun abort() {}
}

/**
 * logcat de la app durante el flow y el buffer de crashes al final.
 * - Antes del flow, los buffers de logcat suben a [bufferSize] (`logcat -G`): `adb shell input` y los reinicios de la app
 *   lo llenan, y con el buffer chico se pierden líneas antes de que se lean. Al final cada buffer vuelve a su tamaño.
 * - Luego `logcat -c`: así un crash en el buffer es de este flow.
 * - Durante el flow, un solo `logcat -v threadtime --uid=<uid de la app>` corriendo hacia `logcat.txt`: solo los logs de
 *   la app, no los de otras apps del dispositivo. Leer con `logcat -d` en un bucle pierde líneas; esto no. Android 9 o
 *   anterior no tiene `--uid`: ahí se guarda todo.
 * - Al final, `logcat -b crash -d`: si hay un crash de la app, va a `crash.txt` y el flow queda marcado.
 * - La corrida puede morir con los buffers agrandados y el `adb logcat` abierto. Para que eso no pase: [Cleanup] lo deshace
 *   si se corta con Ctrl+C, [abort] si Maestro falla, y [stateDir] guarda lo necesario para que la siguiente corrida lo
 *   deshaga si la anterior murió sin poder hacerlo (`kill -9`). Ver [StaleCapture].
 * El logcat crudo se escribe fuera de `evidence/`; solo entra sanitizado. Con [keepRaw] queda en `raw/`.
 */
class LogCapture(
    private val adb: Path,
    private val serial: String,
    private val packageName: String,
    private val uid: Int?,
    private val sdk: Int?,
    private val runner: CommandRunner,
    private val keepRaw: Boolean = false,
    /** Dónde anota cada corrida su captura en curso (`~/.testigo/run`). null: no se anota ni se busca una anterior. */
    private val stateDir: Path? = null,
    /** Tamaño de los buffers de logcat durante el flow, como lo pide `logcat -G` (`16M`). null: no se cambian. */
    private val bufferSize: String? = DEFAULT_BUFFER,
    private val startProcess: (List<String>, Path) -> Process = { command, output ->
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start()
    },
) : LogCollector {
    private val store = stateDir?.let { CaptureStore(it.resolve("logcat-" + serial.replace(Regex("[^A-Za-z0-9._-]"), "_"))) }
    private val self = ProcessHandle.current().pid()

    /** Lo que esta captura le debe al dispositivo: sus buffers originales y su `adb logcat`. */
    private var record = CaptureRecord(ownerPid = self)
    private var process: Process? = null
    private var raw: Path? = null
    private val notes = mutableListOf<String>()

    override fun start(dir: Path) {
        try {
            begin()
        } catch (e: Throwable) {
            abort()
            throw e
        }
    }

    private fun begin() {
        Cleanup.register(this) { abort() }
        store?.let { notes += StaleCapture.close(it, ::restore) }
        enlargeBuffer()
        runner.run(adb(listOf("logcat", "-c")), 30.seconds)
        val filter = if (uid != null && (sdk ?: 0) >= UID_FILTER_MIN_SDK) listOf("--uid=$uid") else emptyList()
        val file = Files.createTempFile("testigo-logcat-", ".txt").also { raw = it }
        val started = startProcess(adb(listOf("logcat", "-v", "threadtime") + filter), file)
        process = started
        update(record.copy(logcatPid = started.pid()))
    }

    override fun finish(dir: Path, redactor: Redactor): LogOutcome {
        process?.let {
            it.destroy()
            if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        val logcat = raw?.takeIf { it.exists() }?.let { file ->
            dir.resolve("logcat.txt").also { it.writeText(redactor.text(EvidenceFiles.read(file))) }
        }
        raw?.let { RawEvidence.keepOrDelete(it, dir, "logcat.txt", keepRaw) }

        // Primero se lee el buffer de crashes: devolver los buffers a un tamaño menor poda lo más viejo de cada uno.
        val buffer = try {
            runner.run(adb(listOf("logcat", "-b", "crash", "-d", "-v", "threadtime")), 30.seconds)
        } finally {
            if (!release()) notes += "no pude devolver los buffers de logcat a su tamaño (adb no contestó): la próxima corrida lo hace"
            Cleanup.unregister(this)
        }
        val note = notes.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        val crash = buffer.takeIf { it.ok }?.let { CrashLog.extract(it.stdout, packageName) }
            ?: return LogOutcome(logcat, null, null, note)
        val crashFile = dir.resolve("crash.txt").also { it.writeText(redactor.text(crash)) }
        return LogOutcome(logcat, crashFile, redactor.text(CrashLog.summary(crash)), note)
    }

    override fun abort() {
        runCatching {
            process?.let {
                it.destroyForcibly()
                it.waitFor(5, TimeUnit.SECONDS)
            }
        }
        runCatching { release() }
        runCatching { raw?.deleteIfExists() }
        Cleanup.unregister(this)
    }

    /**
     * Sube los buffers a [bufferSize]. Un dispositivo que no lo deja cambiar (algunos de fábrica) sigue con el suyo: no es
     * un error, solo hay más riesgo de perder líneas. Si no se puede leer el tamaño que tenían, no se agrandan: no habría
     * cómo devolverlos, y `logcat -G` se queda aunque el dispositivo se reinicie.
     */
    private fun enlargeBuffer() {
        val size = bufferSize ?: return
        // Si otra corrida viva ya los agrandó, lo que dice `logcat -g` no es lo original: vale lo que ella anotó.
        val original = store?.liveBuffers().orEmpty().ifEmpty {
            runCatching { runner.run(adb(listOf("logcat", "-g")), 30.seconds) }.getOrNull()
                ?.takeIf { it.ok }?.let { LogBuffer.parseSizes(it.stdout) }.orEmpty()
        }
        if (original.isEmpty()) {
            notes += "no pude leer el tamaño del buffer de logcat (logcat -g), así que no lo agrandé: si faltan líneas, es por eso"
            return
        }
        // Se anota antes de tocar el dispositivo: si la corrida muere justo después, la siguiente sabe cómo estaba.
        update(record.copy(buffers = original))
        val set = runCatching { runner.run(adb(listOf("logcat", "-G", size)), 30.seconds) }.getOrNull()
        if (set != null && !set.ok) {
            update(record.copy(buffers = emptyMap()))
            notes += "el dispositivo no dejó agrandar el buffer de logcat a $size: si faltan líneas, es por eso"
        }
    }

    /**
     * Devuelve el dispositivo a como estaba y borra lo anotado. Los buffers solo los devuelve la última corrida en salir:
     * si otra sigue viva sobre este dispositivo, los necesita agrandados. false si adb no contestó: queda anotado.
     */
    private fun release(): Boolean {
        val file = store?.file(self)
        val sizes = record.buffers
        if (sizes.isNotEmpty() && (store == null || !store.othersAlive(file!!))) {
            if (!restore(sizes)) {
                update(record.copy(logcatPid = null))
                return false
            }
        }
        record = record.copy(logcatPid = null, buffers = emptyMap())
        file?.let { store?.delete(it) }
        return true
    }

    /** Cada buffer vuelve a su propio tamaño; los que tenían el mismo se piden juntos. false si adb no contestó. */
    private fun restore(sizes: Map<String, String>): Boolean {
        var reached = true
        sizes.entries.groupBy({ it.value }, { it.key }).forEach { (size, buffers) ->
            val ran = runCatching { runner.run(adb(listOf("logcat", "-b", buffers.joinToString(","), "-G", size)), 15.seconds) }
            if (ran.isFailure) reached = false
        }
        return reached
    }

    private fun update(updated: CaptureRecord) {
        record = updated
        store?.let { runCatching { it.write(it.file(self), updated) } }
    }

    private fun adb(args: List<String>) = listOf(adb.toString(), "-s", serial) + args

    companion object {
        /** `logcat --uid` existe desde Android 10. */
        const val UID_FILTER_MIN_SDK = 29

        /** Los emuladores y muchos teléfonos traen 256 KiB: una ráfaga de `input` lo llena antes de que se lea. */
        const val DEFAULT_BUFFER = "16M"
    }
}

/** Lee lo que imprime `logcat -g`. */
internal object LogBuffer {
    private val SIZE = Regex("""^\s*(\w+):\s*ring buffer is (\d+(?:\.\d+)?)\s*([KMG])i?[Bb]""", RegexOption.MULTILINE)

    /**
     * Una línea por buffer: `main: ring buffer is 256 KiB (13 KiB consumed)…` → `main` a `256K`, el tamaño como lo acepta
     * `logcat -G`. Vacío si no se entiende nada.
     */
    fun parseSizes(output: String): Map<String, String> {
        val sizes = linkedMapOf<String, String>()
        for (match in SIZE.findAll(output)) {
            val amount = match.groupValues[2].toDoubleOrNull() ?: continue
            val unit = when (match.groupValues[3]) {
                "G" -> 1024 * 1024
                "M" -> 1024
                else -> 1
            }
            val kib = Math.round(amount * unit)
            sizes[match.groupValues[1]] = when {
                kib % (1024 * 1024) == 0L -> "${kib / (1024 * 1024)}G"
                kib % 1024 == 0L -> "${kib / 1024}M"
                else -> "${kib}K"
            }
        }
        return sizes
    }
}

/** Lee el buffer de crashes de logcat (formato threadtime). */
internal object CrashLog {
    /** `10-16 14:55:10.123  1234  1234 E AndroidRuntime: mensaje` → pid, tag y mensaje. */
    private val LINE = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+\w\s+(.*?)\s*: (.*)$""")
    private val EXCEPTION = Regex("""^[\w.$]+(Exception|Error|Throwable)\b.*""")

    /**
     * Solo las líneas del crash de [packageName]: las del proceso que dice `Process: <package>, PID` (Java/Kotlin)
     * o `>>> <package> <<<` (crash nativo). null si la app no crasheó.
     */
    fun extract(buffer: String, packageName: String): String? {
        val lines = buffer.lines()
        val pids = lines
            .filter { "Process: $packageName," in it || ">>> $packageName <<<" in it }
            .mapNotNull { LINE.find(it)?.groupValues?.get(1) }
            .toSet()
        if (pids.isEmpty()) return null
        return lines.filter { LINE.find(it)?.groupValues?.get(1) in pids }.joinToString("\n")
    }

    /** La excepción (`java.lang.IllegalStateException: …`) o la señal de un crash nativo. */
    fun summary(crash: String): String {
        val messages = crash.lines().mapNotNull { LINE.find(it)?.groupValues?.get(3)?.trim() }
        return messages.firstOrNull { EXCEPTION.matches(it) }
            ?: messages.firstOrNull { it.startsWith("signal ") }
            ?: "la app se cerró inesperadamente"
    }
}
