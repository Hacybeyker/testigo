package testigo.maestro

import testigo.ExitCode
import testigo.logs.LogCollector
import testigo.network.NetworkCollector
import testigo.network.NetworkOutcome
import testigo.process.Cleanup
import testigo.redact.EvidenceFiles
import testigo.redact.EvidenceStatus
import testigo.redact.Leak
import testigo.redact.LeakScanner
import testigo.redact.RawEvidence
import testigo.redact.RedactRules
import testigo.redact.Redactor
import testigo.report.SelfContainedReport
import testigo.video.Recorder
import testigo.video.VideoSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

enum class FlowStatus(val symbol: String, val label: String) {
    PASSED("✓", "pasó"),
    FAILED("✗", "falló"),
}

data class FlowResult(
    val name: String,
    val flow: FlowFile,
    val dir: Path,
    val status: FlowStatus,
    val duration: Duration,
    val report: Path?,
    /** Por qué falló, ya sin valores de variables. null si pasó. */
    val reason: String?,
    val videos: List<Path> = emptyList(),
    /** Por qué no hay video, si se pidió y no se pudo grabar. */
    val videoNote: String? = null,
    val logcat: Path? = null,
    /** Resumen del crash de la app (la excepción), si hubo. */
    val crash: String? = null,
    val crashFile: Path? = null,
    /** Red del flow; null si no se pidió. */
    val network: NetworkOutcome? = null,
    /** Lo que el leak scan encontró sin enmascarar en la evidencia del flow. */
    val leaks: List<Leak> = emptyList(),
    /** Archivos binarios (video, capturas): no se pueden sanitizar. */
    val unscanned: List<Path> = emptyList(),
    /** Con `--repeat`: en qué ronda corrió, desde 1. */
    val attempt: Int = 1,
)

data class RunResult(
    val dir: Path,
    val flows: List<FlowResult>,
    val duration: Duration,
    /** `--keep-raw`: cada flow tiene datos sin sanitizar en `raw/`. */
    val keepRaw: Boolean = false,
    /** Con `--redact`: la evidencia se sanitizó y pasó el leak scan. */
    val sanitized: Boolean = false,
    val startedAt: ZonedDateTime = ZonedDateTime.now(),
    /** `--repeat`: cuántas rondas corrieron. [flows] trae una entrada por corrida, ronda tras ronda. */
    val repeat: Int = 1,
) {
    /** Nombre de la carpeta de la corrida: `20261016-201344_PROJ-123_a1b2c3d`. */
    val id: String get() = dir.fileName.toString()
    val failed: Int get() = flows.size - passed

    val passed: Int get() = flows.count { it.status == FlowStatus.PASSED }
    val leaks: List<Leak> get() = flows.flatMap { it.leaks }

    /** Cada flow con sus corridas, en el orden de la primera ronda. */
    val stability: List<FlowStability> get() = flows.groupBy { it.name }.map { (name, runs) -> FlowStability(name, runs) }

    /** Los flows que pasaron unas veces y fallaron otras. */
    val flaky: List<String> get() = stability.filter { it.flaky }.map { it.name }

    /** Cómo se nombra una corrida: `login` o, con `--repeat`, `login #2`. */
    fun label(flow: FlowResult): String = if (repeat > 1) "${flow.name} #${flow.attempt}" else flow.name
    val evidenceStatus: EvidenceStatus
        get() = when {
            !sanitized -> EvidenceStatus.SIN_SANITIZAR
            leaks.isEmpty() -> EvidenceStatus.OK
            else -> EvidenceStatus.NO_APTA
        }

    /** Un leak pesa más que un flow fallido: la evidencia no se puede compartir. */
    val exitCode: ExitCode
        get() = when {
            leaks.isNotEmpty() -> ExitCode.LEAK
            passed == flows.size -> ExitCode.OK
            else -> ExitCode.FLOW_FAILED
        }
}

/** Las corridas de un flow con `--repeat`: si pasó siempre, a veces o nunca. */
data class FlowStability(val name: String, val runs: List<FlowResult>) {
    val passed: Int get() = runs.count { it.status == FlowStatus.PASSED }
    val stable: Boolean get() = passed == runs.size
    val flaky: Boolean get() = passed in 1 until runs.size
    val label: String get() = when {
        stable -> "estable"
        flaky -> "intermitente"
        else -> "falla siempre"
    }
    val meanDuration: Duration get() = runs.map { it.duration }.reduce(Duration::plus) / runs.size
}

/**
 * Paso 4 del ciclo de una corrida: un `maestro test` por flow, cada uno con su carpeta de evidencia.
 * Así la evidencia de cada flow queda aislada aunque otro falle.
 * Con [rules], todo lo de texto se sanitiza antes de entrar a `evidence/` y al final se pasa el leak scan.
 */
class FlowExecutor(
    private val maestro: MaestroCli,
    /** null: sin video. Si no, crea el grabador para el dispositivo de la corrida. */
    private val recorder: ((device: String) -> Recorder)? = null,
    /** null: sin logs. Si no, crea la captura de logcat y crashes para el dispositivo. */
    private val logs: ((device: String) -> LogCollector)? = null,
    /** null: sin red. Si no, crea la captura de red (Chucker) para el dispositivo. */
    private val network: ((device: String) -> NetworkCollector)? = null,
    /** null (por defecto): sin sanitizar, la evidencia queda tal cual para replicar los requests, y sin leak scan. */
    private val rules: RedactRules? = null,
    /** `--keep-raw`: los crudos de Maestro quedan en `<flow>/raw/maestro/`. */
    private val keepRaw: Boolean = false,
    private val timeout: Duration = MaestroCli.DEFAULT_TIMEOUT,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val progress: (String) -> Unit = {},
) {
    /**
     * [appId] se usa en el wrapper de video cuando el flow no lo declara. [runSuffix] va después de la fecha en el
     * nombre de la carpeta de la corrida (ticket y commit). Con [repeat] mayor a 1, la lista entera corre esas veces, ronda
     * tras ronda, y cada corrida deja su evidencia en `<flow>/<ronda>/`.
     */
    fun run(
        flows: List<FlowFile>,
        device: String,
        env: Map<String, String>,
        evidenceRoot: Path,
        appId: String,
        runSuffix: String = "",
        repeat: Int = 1,
    ): RunResult {
        require(repeat >= 1) { "repeat debe ser 1 o más: $repeat" }
        val started = System.nanoTime()
        val startedAt = now()
        val runDir = createRunDir(evidenceRoot, startedAt.format(RUN_ID) + runSuffix)
        val names = uniqueNames(flows)
        val redactor = rules?.let { Redactor(it, env) } ?: Redactor.NONE
        val total = flows.size * repeat
        val results = (1..repeat).flatMap { attempt ->
            flows.mapIndexed { index, flow ->
                val name = names[index]
                val position = (attempt - 1) * flows.size + index + 1
                val round = if (repeat > 1) " · ronda $attempt de $repeat" else ""
                progress("▶ $name ($position/$total$round)")
                val dir = if (repeat > 1) runDir.resolve(name).resolve(attempt.toString()) else runDir.resolve(name)
                runFlow(flow, name, dir, device, env, redactor, appId, reinstallDriver = position == 1, attempt = attempt).also {
                    val crash = if (it.crash != null) " (crash de la app)" else ""
                    val leaks = if (it.leaks.isNotEmpty()) " · datos sin enmascarar" else ""
                    progress("  ${it.status.symbol} ${it.status.label}$crash · ${format(it.duration)}$leaks")
                }
            }
        }
        return RunResult(
            dir = runDir,
            flows = results,
            duration = (System.nanoTime() - started).nanoseconds,
            keepRaw = keepRaw,
            sanitized = rules != null,
            startedAt = startedAt.atZone(ZoneId.systemDefault()),
            repeat = repeat,
        )
    }

    private fun runFlow(
        flow: FlowFile,
        name: String,
        dir: Path,
        device: String,
        env: Map<String, String>,
        redactor: Redactor,
        appId: String,
        reinstallDriver: Boolean,
        attempt: Int,
    ): FlowResult {
        dir.createDirectories()
        // Maestro escribe fuera de evidence/: a la carpeta del flow solo entra lo ya sanitizado.
        val work = Files.createTempDirectory("testigo-maestro-")
        Cleanup.register(work) { deleteTree(work) }
        val rawOutput = work.resolve(RAW_OUTPUT).createDirectories()
        val console = work.resolve(RAW_CONSOLE)
        val video = recorder?.let { VideoSession(it(device), flow, appId) }
        val logCollector = logs?.invoke(device)
        logCollector?.start(dir)
        val networkCollector = network?.invoke(device)
        networkCollector?.start()
        val started = System.nanoTime()
        val outcome = try {
            maestro.test(
                MaestroTest(
                    flow = video?.wrapper ?: flow.path,
                    device = device,
                    env = env,
                    report = work.resolve(RAW_REPORT),
                    outputDir = rawOutput,
                    console = console,
                    reinstallDriver = reinstallDriver,
                    timeout = timeout,
                ),
            )
        } catch (e: Exception) {
            deleteTree(work)
            Cleanup.unregister(work)
            throw e
        } finally {
            video?.close()
        }
        val duration = (System.nanoTime() - started).nanoseconds
        val videoOutcome = video?.finish(dir)
        val logOutcome = logCollector?.finish(dir, redactor)
        val networkOutcome = networkCollector?.finish(dir, redactor)
        val consoleText = try {
            collect(dir, work, redactor)
        } finally {
            deleteTree(work)
            Cleanup.unregister(work)
        }

        val report = dir.resolve("report.html")
        val hasReport = report.isRegularFile()
        val maestroReason = when {
            outcome.timedOut -> "superó el tiempo máximo de ${format(timeout)}"
            outcome.exitCode == 0 && hasReport -> null
            outcome.exitCode == 0 -> "Maestro terminó sin generar report.html"
            else -> lastLines(consoleText) ?: "Maestro terminó con código ${outcome.exitCode}"
        }
        // Un crash de la app es un fallo aunque Maestro haya terminado bien.
        val reason = listOfNotNull(logOutcome?.crash?.let { "crash de la app: $it" }, maestroReason)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
        val scan = rules?.let { LeakScanner.scan(dir, redactor) }
        return FlowResult(
            name = name,
            flow = flow,
            dir = dir,
            status = if (reason == null) FlowStatus.PASSED else FlowStatus.FAILED,
            duration = duration,
            report = report.takeIf { hasReport },
            reason = reason,
            videos = videoOutcome?.files.orEmpty(),
            videoNote = videoOutcome?.note,
            logcat = logOutcome?.logcat,
            crash = logOutcome?.crash,
            crashFile = logOutcome?.crashFile,
            network = networkOutcome,
            leaks = scan?.leaks.orEmpty(),
            unscanned = scan?.unscanned.orEmpty(),
            attempt = attempt,
        )
    }

    /**
     * Pasa lo de Maestro a la carpeta del flow, sanitizado: `report.html` (además autocontenido) y, en
     * `<flow>/maestro/`, lo que escribió en `--test-output-dir` (que agrega una carpeta con fecha) y su consola.
     * Los binarios (capturas) se copian tal cual: no se pueden sanitizar.
     */
    private fun collect(dir: Path, work: Path, redactor: Redactor): String {
        val target = dir.resolve("maestro").createDirectories()
        val output = work.resolve(RAW_OUTPUT)
        val root = output.listDirectoryEntries().singleOrNull()?.takeIf { it.isDirectory() } ?: output
        // Maestro guarda el logcat de todo el dispositivo, con logs de otras apps. El de la app es logcat.txt.
        val files = Files.walk(root).use { paths -> paths.filter { it.isRegularFile() }.toList() }
            .filter { it.fileName.toString() != MAESTRO_DEVICE_LOG }
        files.forEach { file ->
            val destination = target.resolve(root.relativize(file).toString())
            destination.parent.createDirectories()
            if (EvidenceFiles.isText(file)) {
                destination.writeText(redactor.text(EvidenceFiles.read(file)))
            } else {
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        val console = work.resolve(RAW_CONSOLE)
        val text = if (console.exists()) redactor.text(EvidenceFiles.read(console)) else ""
        target.resolve("console.txt").writeText(text)

        val report = work.resolve(RAW_REPORT)
        if (report.isRegularFile()) {
            // El reporte de Maestro carga Bootstrap desde un CDN: abrirlo saldría a internet.
            val html = SelfContainedReport.rewrite(EvidenceFiles.read(report))
            dir.resolve("report.html").writeText(redactor.text(html))
        }

        if (keepRaw) {
            Files.walk(work).use { paths -> paths.filter { it.isRegularFile() }.toList() }.forEach { file ->
                RawEvidence.keepOrDelete(file, dir, "maestro/" + work.relativize(file).toString(), keep = true)
            }
        }
        return text
    }

    private fun createRunDir(root: Path, base: String): Path {
        root.createDirectories()
        // La evidencia trae tokens y datos de la app: nunca debe terminar en un commit, aunque falte en el .gitignore.
        root.resolve(".gitignore").takeIf { !it.exists() }?.writeText(GITIGNORE)
        var dir = root.resolve(base)
        var suffix = 2
        while (dir.exists()) dir = root.resolve("$base-${suffix++}")
        return dir.createDirectories()
    }

    companion object {
        private const val MAESTRO_DEVICE_LOG = "device-logcat.txt"
        internal const val GITIGNORE = "# Generado por testigo: la evidencia no se sube al repo.\n*\n"
        private const val RAW_OUTPUT = "output"
        private const val RAW_CONSOLE = "console.txt"
        private const val RAW_REPORT = "report.html"
        private val RUN_ID: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

        /** Nombre de la carpeta de cada flow: su `name` o el nombre del archivo, sin repetir. */
        internal fun uniqueNames(flows: List<FlowFile>): List<String> {
            val used = mutableMapOf<String, Int>()
            return flows.map { flow ->
                val base = (flow.header.name ?: flow.path.nameWithoutExtension)
                    .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                    .trim('_')
                    .ifEmpty { "flow" }
                val count = used.merge(base, 1, Int::plus)!!
                if (count == 1) base else "$base-$count"
            }
        }

        private val FAILURE_MARKERS = listOf("[Failed]", "❌", "Assertion is false", "Element not found", "Error", "Exception")

        /** Lo que dice la consola de Maestro sobre el fallo: las líneas con error o, si no hay, las últimas. */
        internal fun lastLines(console: String, count: Int = 3): String? {
            val lines = console.lines()
                .map { it.replace(ANSI, "").trim().trimStart('║', '│', ' ') }
                .filter { it.isNotEmpty() && !it.startsWith("====") && !it.startsWith("/") && !Regex("^[A-Za-z]:\\\\").containsMatchIn(it) }
            val failures = lines.filter { line -> FAILURE_MARKERS.any { it in line } }
            return failures.ifEmpty { lines }.takeLast(count).takeIf { it.isNotEmpty() }?.joinToString("\n")
        }

        fun format(duration: Duration): String {
            val seconds = duration.inWholeMilliseconds / 1000.0
            return if (seconds < 60) {
                "%.1f s".format(java.util.Locale.ROOT, seconds)
            } else {
                "${duration.inWholeMinutes} min ${duration.inWholeSeconds % 60} s"
            }
        }

        private fun deleteTree(path: Path) {
            if (!path.exists()) return
            Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
