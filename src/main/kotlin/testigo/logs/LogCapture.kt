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

/** Lo que quedó de logs de un flow. [crash] es el resumen del crash de la app, si hubo. */
data class LogOutcome(val logcat: Path?, val crashFile: Path?, val crash: String?)

/** Captura de logs alrededor de un flow. Una interfaz para poder probar sin dispositivo. */
interface LogCollector {
    fun start(dir: Path)

    /** Todo lo que se escribe pasa antes por [redactor]. */
    fun finish(dir: Path, redactor: Redactor): LogOutcome
}

/**
 * logcat de la app durante el flow y el buffer de crashes al final.
 * - Antes del flow, `logcat -c`: así un crash en el buffer es de este flow.
 * - Durante el flow, `logcat -v threadtime --uid=<uid de la app>` hacia `logcat.txt`: solo los logs de la app,
 *   no los de otras apps del dispositivo. Android 9 o anterior no tiene `--uid`: ahí se guarda todo.
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
    private val startProcess: (List<String>, Path) -> Process = { command, output ->
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start()
    },
) : LogCollector {
    private var process: Process? = null
    private var raw: Path? = null

    override fun start(dir: Path) {
        runner.run(adb(listOf("logcat", "-c")), 30.seconds)
        val filter = if (uid != null && (sdk ?: 0) >= UID_FILTER_MIN_SDK) listOf("--uid=$uid") else emptyList()
        val file = Files.createTempFile("testigo-logcat-", ".txt").also { raw = it }
        process = startProcess(adb(listOf("logcat", "-v", "threadtime") + filter), file)
        Cleanup.register(this) {
            process?.destroyForcibly()
            file.deleteIfExists()
        }
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
        Cleanup.unregister(this)

        val buffer = runner.run(adb(listOf("logcat", "-b", "crash", "-d", "-v", "threadtime")), 30.seconds)
        val crash = buffer.takeIf { it.ok }?.let { CrashLog.extract(it.stdout, packageName) }
            ?: return LogOutcome(logcat, null, null)
        val crashFile = dir.resolve("crash.txt").also { it.writeText(redactor.text(crash)) }
        return LogOutcome(logcat, crashFile, redactor.text(CrashLog.summary(crash)))
    }

    private fun adb(args: List<String>) = listOf(adb.toString(), "-s", serial) + args

    companion object {
        /** `logcat --uid` existe desde Android 10. */
        const val UID_FILTER_MIN_SDK = 29
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
