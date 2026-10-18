package testigo.network

import testigo.BuildInfo
import testigo.android.AppFiles
import testigo.config.NetworkFormat
import testigo.process.Cleanup
import testigo.process.CommandRunner
import testigo.redact.RawEvidence
import testigo.redact.Redactor
import testigo.util.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/** Lo que quedó de la red de un flow. [note] explica por qué no hay HAR, si no lo hay. */
data class NetworkOutcome(
    val har: Path? = null,
    val text: Path? = null,
    val summaryFile: Path? = null,
    val summary: NetworkSummary? = null,
    val note: String? = null,
    /** Las alertas del resumen, en una línea cada una, para mostrarlas en la consola. */
    val highlights: List<String> = emptyList(),
)

/** Captura de red alrededor de un flow. Una interfaz para poder probar sin dispositivo. */
interface NetworkCollector {
    fun start()

    /** Todo lo que se escribe pasa antes por [redactor]. */
    fun finish(dir: Path, redactor: Redactor): NetworkOutcome
}

/**
 * Red del flow leída de la base de Chucker de la app: con `run-as` si el build es debuggable o, si no, cuando adbd
 * ya corre como root (ver [AppFiles]).
 * 1. Al empezar, guarda la hora del dispositivo: Chucker usa ese reloj.
 * 2. Al terminar, espera a que la base deje de crecer (Room escribe en segundo plano).
 * 3. Copia la base con su `-wal` y `-shm` (sin el WAL faltarían las últimas llamadas) con `adb exec-out`.
 * 4. Toma solo las transacciones desde el inicio del flow, las sanitiza y escribe `network.txt` y/o
 *    `network.har` según [format], más `network-summary.json`.
 * La copia cruda queda fuera de `evidence/` y se borra, salvo con `--keep-raw` (queda en `raw/`).
 */
class ChuckerCapture(
    private val adb: Path,
    private val serial: String,
    private val packageName: String,
    /** `auto` o el nombre del archivo, como `chucker.db`. */
    private val database: String,
    private val hosts: List<String>,
    private val slowMs: Long,
    private val keepRaw: Boolean,
    private val runner: CommandRunner,
    private val format: NetworkFormat = NetworkFormat.DEFAULT,
    /** Ejecuta un comando y guarda su salida binaria en el archivo. true si terminó bien. */
    private val copy: (List<String>, Path) -> Boolean = ::execOut,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : NetworkCollector {
    private var since: Long? = null

    override fun start() {
        since = deviceMillis()
    }

    override fun finish(dir: Path, redactor: Redactor): NetworkOutcome {
        val access = AppFiles.detect(packageName) { args -> runner.run(adb("shell") + args, 30.seconds) }
        val app = access.files ?: return NetworkOutcome(
            note = "run-as falló en $packageName (${access.runAsError}): el build tiene que ser debuggable, " +
                "o adbd correr como root (adb root en un emulador con imagen Google APIs)",
        )
        val listing = runner.run(adb("shell") + app.command("ls", app.path("databases")), 30.seconds)
        if (!listing.ok) {
            return NetworkOutcome(note = "no se pudo listar databases/ de $packageName con ${app.mode.label}: ${listing.output.lines().first()}")
        }
        val files = listing.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = databaseName(files, database) ?: return NetworkOutcome(note = "la app no tiene base de Chucker (¿este build trae Chucker?, ¿hizo alguna llamada?)")

        waitUntilStable(app, name)
        val raw = Files.createTempDirectory("testigo-chucker-")
        // La copia cruda no debe quedar en /tmp si la corrida se corta.
        Cleanup.register(raw) {
            raw.listDirectoryEntries().forEach { it.deleteIfExists() }
            raw.deleteIfExists()
        }
        try {
            listOf(name, "$name-wal", "$name-shm").filter { it in files }.forEach { file ->
                val target = raw.resolve(file)
                if (!copy(adb("exec-out") + app.command("cat", app.path("databases/$file")), target)) target.deleteIfExists()
            }
            if (!raw.resolve(name).isRegularFile()) return NetworkOutcome(note = "no se pudo copiar la base de Chucker con ${app.mode.label}")

            val transactions = ChuckerDatabase.read(raw.resolve(name), since).map { it.redacted(redactor) }
            val summary = NetworkSummary.of(transactions, hosts, slowMs)
            val har = if (format.har) {
                dir.resolve("network.har").also { it.writeText(Json.write(Har.build(transactions, BuildInfo.VERSION))) }
            } else {
                null
            }
            val text = if (format.text) {
                dir.resolve("network.txt").also { it.writeText(NetworkText.render(transactions, summary, sanitized = redactor.active)) }
            } else {
                null
            }
            val summaryFile = dir.resolve("network-summary.json").also { it.writeText(Json.write(summary.toJson())) }
            val note = if (since == null) "no se pudo leer la hora del dispositivo: el HAR puede traer llamadas de flows anteriores" else null
            raw.listDirectoryEntries().forEach { RawEvidence.keepOrDelete(it, dir, it.fileName.toString(), keepRaw) }
            return NetworkOutcome(har, text, summaryFile, summary, note, summary.alertLines())
        } catch (e: java.sql.SQLException) {
            return NetworkOutcome(note = "no se pudo leer la base de Chucker: ${e.message}")
        } finally {
            raw.listDirectoryEntries().forEach { it.deleteIfExists() }
            raw.deleteIfExists()
            Cleanup.unregister(raw)
        }
    }

    /** Hora del dispositivo en ms. `date +%s%3N` no existe en todos los Android: si falla, segundos × 1000. */
    private fun deviceMillis(): Long? {
        val millis = runner.run(adb("shell", "date", "+%s%3N"), 10.seconds).stdout.trim().toLongOrNull()
        if (millis != null && millis > 100_000_000_000L) return millis
        return runner.run(adb("shell", "date", "+%s"), 10.seconds).stdout.trim().toLongOrNull()?.times(1000)
    }

    /** Espera hasta que el tamaño de la base y de su WAL no cambie entre dos lecturas (máximo ~10 s). */
    private fun waitUntilStable(app: AppFiles, name: String) {
        var previous: String? = null
        repeat(STABILITY_CHECKS) {
            val listing = app.command("ls", "-l", app.path("databases/$name"), app.path("databases/$name-wal"))
            val current = runner.run(adb("shell") + listing, 30.seconds).stdout
            if (current == previous) return
            previous = current
            sleep(STABILITY_INTERVAL_MS)
        }
    }

    private fun adb(vararg args: String) = listOf(adb.toString(), "-s", serial) + args

    companion object {
        private val CHUCKER_DB = Regex("chucker.*\\.db")
        private const val STABILITY_CHECKS = 20
        private const val STABILITY_INTERVAL_MS = 500L

        /** La base de Chucker entre los archivos de `databases/`: la que pide [configured] o, con `auto`, `chucker*.db`. */
        fun databaseName(files: List<String>, configured: String): String? = when (configured) {
            "auto" -> files.firstOrNull { CHUCKER_DB.matches(it) }
            else -> configured.takeIf { it in files }
        }

        /** `adb exec-out` es binario: la salida va directo al archivo, sin pasar por texto. */
        fun execOut(command: List<String>, target: Path): Boolean {
            val process = ProcessBuilder(command)
                .redirectOutput(target.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return false
            }
            return process.exitValue() == 0 && target.isRegularFile() && target.fileSize() > 0
        }
    }
}
