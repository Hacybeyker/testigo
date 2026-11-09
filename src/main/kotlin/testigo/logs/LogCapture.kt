package testigo.logs

import testigo.process.Cleanup
import testigo.process.CommandRunner
import testigo.redact.EvidenceFiles
import testigo.redact.RawEvidence
import testigo.redact.Redactor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
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
}

/**
 * logcat de la app durante el flow y el buffer de crashes al final.
 * - Antes del flow, el buffer de logcat sube a [bufferSize] (`logcat -G`): `adb shell input` y los reinicios de la app lo
 *   llenan, y con el buffer chico se pierden líneas antes de que se lean. Al final se deja el tamaño que tenía.
 * - Luego `logcat -c`: así un crash en el buffer es de este flow.
 * - Durante el flow, un solo `logcat -v threadtime --uid=<uid de la app>` corriendo hacia `logcat.txt`: solo los logs de
 *   la app, no los de otras apps del dispositivo. Leer con `logcat -d` en un bucle pierde líneas; esto no. Android 9 o
 *   anterior no tiene `--uid`: ahí se guarda todo.
 * - Si una corrida anterior murió sin limpiar y su captura seguía abierta, [pidFile] la delata y se cierra antes de
 *   empezar, para que no escriba líneas repetidas ni gaste el dispositivo.
 * - Al final, `logcat -b crash -d`: si hay un crash de la app, va a `crash.txt` y el flow queda marcado.
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
    /** Dónde anotar el pid de la captura en curso. null: no se anota ni se busca una anterior. */
    private val pidFile: Path? = null,
    /** Tamaño del buffer de logcat durante el flow, como lo pide `logcat -G` (`16M`). null: no se cambia. */
    private val bufferSize: String? = DEFAULT_BUFFER,
    private val startProcess: (List<String>, Path) -> Process = { command, output ->
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start()
    },
) : LogCollector {
    private var process: Process? = null
    private var raw: Path? = null

    /** El tamaño que tenía el buffer antes de agrandarlo; null si no se cambió o no se pudo leer. */
    private var restoreBuffer: String? = null
    private val notes = mutableListOf<String>()

    override fun start(dir: Path) {
        StaleCapture.close(pidFile)?.let { notes += it }
        enlargeBuffer()
        runner.run(adb(listOf("logcat", "-c")), 30.seconds)
        val filter = if (uid != null && (sdk ?: 0) >= UID_FILTER_MIN_SDK) listOf("--uid=$uid") else emptyList()
        val file = Files.createTempFile("testigo-logcat-", ".txt").also { raw = it }
        val started = startProcess(adb(listOf("logcat", "-v", "threadtime") + filter), file)
        process = started
        StaleCapture.record(pidFile, started.pid())
        Cleanup.register(this) {
            process?.destroyForcibly()
            pidFile?.deleteIfExists()
            file.deleteIfExists()
        }
    }

    override fun finish(dir: Path, redactor: Redactor): LogOutcome {
        process?.let {
            it.destroy()
            if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        pidFile?.let { runCatching { it.deleteIfExists() } }
        restoreBuffer?.let { size -> runCatching { runner.run(adb(listOf("logcat", "-G", size)), 30.seconds) } }
        val logcat = raw?.takeIf { it.exists() }?.let { file ->
            dir.resolve("logcat.txt").also { it.writeText(redactor.text(EvidenceFiles.read(file))) }
        }
        raw?.let { RawEvidence.keepOrDelete(it, dir, "logcat.txt", keepRaw) }
        Cleanup.unregister(this)

        val note = notes.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        val buffer = runner.run(adb(listOf("logcat", "-b", "crash", "-d", "-v", "threadtime")), 30.seconds)
        val crash = buffer.takeIf { it.ok }?.let { CrashLog.extract(it.stdout, packageName) }
            ?: return LogOutcome(logcat, null, null, note)
        val crashFile = dir.resolve("crash.txt").also { it.writeText(redactor.text(crash)) }
        return LogOutcome(logcat, crashFile, redactor.text(CrashLog.summary(crash)), note)
    }

    /**
     * Sube el buffer a [bufferSize]. Un dispositivo que no lo deja cambiar (algunos de fábrica) sigue con el suyo: no es
     * un error, solo hay más riesgo de perder líneas.
     */
    private fun enlargeBuffer() {
        val size = bufferSize ?: return
        val current = runCatching { runner.run(adb(listOf("logcat", "-g")), 30.seconds) }.getOrNull()
            ?.takeIf { it.ok }?.let { LogBuffer.parseSize(it.stdout) }
        val set = runCatching { runner.run(adb(listOf("logcat", "-G", size)), 30.seconds) }.getOrNull()
        if (set?.ok == true) {
            restoreBuffer = current
        } else {
            notes += "el dispositivo no dejó agrandar el buffer de logcat a $size: si faltan líneas, es por eso"
        }
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
    private val SIZE = Regex("""ring buffer is (\d+(?:\.\d+)?)\s*([KMG])i?[Bb]""")

    /** `main: ring buffer is 256 KiB (13 KiB consumed)…` → `256K`, como lo acepta `logcat -G`. null si no se entiende. */
    fun parseSize(output: String): String? {
        val match = SIZE.find(output) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        return "${Math.round(amount)}${match.groupValues[2]}"
    }
}

/**
 * Una captura de logcat que una corrida anterior dejó abierta. La corrida anota en [pidFile] el pid de su `adb logcat` y el
 * suyo; la siguiente, si encuentra que el dueño ya no existe y el `adb logcat` sí, lo cierra. Si el dueño sigue vivo es otra
 * corrida en marcha sobre el mismo dispositivo: no se toca.
 */
internal object StaleCapture {
    fun record(pidFile: Path?, logcatPid: Long) {
        pidFile ?: return
        runCatching {
            pidFile.parent?.createDirectories()
            pidFile.writeText("$logcatPid ${ProcessHandle.current().pid()}")
        }
    }

    /** Cierra la captura vieja si la hay; devuelve qué hizo, o null si no había nada que cerrar. */
    fun close(pidFile: Path?): String? {
        pidFile ?: return null
        val pids = runCatching { pidFile.readText().trim().split(Regex("\\s+")).map { it.toLong() } }.getOrNull() ?: return null
        runCatching { pidFile.deleteIfExists() }
        val logcatPid = pids.getOrNull(0) ?: return null
        val owner = pids.getOrNull(1)
        if (owner != null && ProcessHandle.of(owner).map { it.isAlive }.orElse(false)) return null
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
