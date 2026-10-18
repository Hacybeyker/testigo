package testigo.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import testigo.TestigoException
import testigo.ExitCode
import testigo.android.AndroidSdk
import testigo.config.ConfigEdit
import testigo.config.ConfigEditor
import testigo.config.ConfigLoader
import testigo.config.ConfigPaths
import testigo.config.LoadedProject
import testigo.config.NetworkFormat
import testigo.config.NetworkMode
import testigo.config.Profile
import testigo.config.RunOptions
import testigo.config.RunPlan
import testigo.config.RunPlanner
import testigo.logs.LogCapture
import testigo.logs.LogCollector
import testigo.maestro.FlowExecutor
import testigo.maestro.MaestroCli
import testigo.maestro.YamlFlowHeaderReader
import testigo.network.ChuckerCapture
import testigo.network.NetworkCollector
import testigo.policy.FlowLinter
import testigo.policy.MaestroEnvironment
import testigo.policy.PolicyReport
import testigo.preflight.TestigoHome
import testigo.preflight.Preflight
import testigo.process.ProcessCommandRunner
import testigo.redact.RedactRules
import testigo.redact.Redactor
import testigo.report.Git
import testigo.report.RunContext
import testigo.report.RunReport
import testigo.video.Recorder
import testigo.video.ScreenRecorder
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class RunCommand : CliktCommand(name = "run") {

    override fun help(context: Context): String =
        "Ejecuta flows de Maestro y genera la evidencia de cada uno. Sin --profile, --flows, --tags ni --app, " +
            "pregunta qué ejecutar. Usa --dry-run para ver el plan sin ejecutar nada."

    private val config by option("--config", help = "Ruta a testigo.yaml. Por defecto se busca desde la carpeta actual hacia arriba.")
        .path()
    private val profile by option("--profile", help = "Atajo de --record, --network y --logs: smoke, dev o evidencia. Por defecto, defaults.profile o dev.")
        .enum<Profile> { it.cliName }
    private val app by option("--app", help = "APK o AAB a probar. Por defecto, app.artifact de testigo.yaml.")
        .path()
    private val flows by option("--flows", help = "Flow o carpeta de flows; se puede repetir. Por defecto, flows de testigo.yaml o flows/.")
        .path()
        .multiple()
    private val tags by option("--tags", help = "Solo flows con alguno de estos tags, separados por coma.")
        .split(",")
    private val excludeTags by option("--exclude-tags", help = "Excluye flows con alguno de estos tags, separados por coma.")
        .split(",")
    private val record by option("--record", help = "Graba un video por flow. Por defecto, según el perfil.")
        .nullableFlag("--no-record")
    private val videoBitrate by option("--video-bitrate", help = "Calidad del video en Mbps, de 4 a 8. Por defecto, video.bitrate_mbps o 4.")
        .int()
    private val network by option("--network", help = "Captura de red desde Chucker: chucker o none. Por defecto, según el perfil y network.capture.")
        .enum<NetworkMode> { it.cliName }
    private val networkFormat by option("--network-format", help = "Formato de la red: txt, har o ambos. Por defecto, network.format o txt.")
        .enum<NetworkFormat> { it.cliName }
    private val logs by option("--logs", help = "Captura logcat y crashes. Por defecto, según el perfil.")
        .nullableFlag("--no-logs")
    private val device by option("--device", help = "Serial de adb del dispositivo. Por defecto, el único conectado.")
    private val env by option("--env", help = "Variable para los flows, KEY=VALOR; se puede repetir y gana sobre .env.testigo.")
        .associate()
    private val envFile by option("--env-file", help = "Archivo de variables. Por defecto, .env.testigo junto a testigo.yaml.")
        .path()
    private val out by option("--out", help = "Carpeta de evidencia. Por defecto, evidence.dir de testigo.yaml o evidence/.")
        .path()
    private val ci by option("--ci", help = "Sin preguntas; genera junit.xml.")
        .flag()
    private val dryRun by option("--dry-run", help = "Muestra el plan y revisa la política, sin tocar el dispositivo ni ejecutar nada.")
        .flag()
    private val keepRaw by option("--keep-raw", help = "Deja en raw/ los datos sin procesar. Solo para depurar. Por defecto, evidence.keep_raw.")
        .flag()
    private val redact by option(
        "--redact",
        help = "Enmascara tokens, claves y datos personales y corre el leak scan. Por defecto, redact.enabled o no.",
    ).nullableFlag("--no-redact")
    private val save by option(
        "--save",
        help = "Guarda en testigo.yaml lo que pasaste con --app, --profile, --flows (uno solo), --video-bitrate, --network-format, " +
            "--redact u --out, para no repetirlo. En el modo interactivo, guarda lo elegido sin preguntar.",
    ).flag()

    override fun run() {
        val cwd = Path.of("").toAbsolutePath().normalize()
        val given = RunOptions(
            config = config,
            profile = profile,
            app = app,
            flows = flows,
            tags = tags.cleaned(),
            excludeTags = excludeTags.cleaned(),
            record = record,
            videoBitrate = videoBitrate,
            network = network,
            networkFormat = networkFormat,
            logs = logs,
            device = device,
            env = env,
            envFile = envFile,
            out = out,
            ci = ci,
            dryRun = dryRun,
            keepRaw = keepRaw,
            redact = redact,
        )

        val plan = try {
            val loaded = ConfigLoader.load(given.config, cwd)
            val interactive = interactive()
            val options = if (interactive) ask(given, loaded, cwd) else given
            // Si se guardó algo en testigo.yaml, el plan lo muestra como parte de testigo.yaml.
            val project = if (savedConfig) ConfigLoader.load(given.config, cwd) else loaded
            RunPlanner(cwd, YamlFlowHeaderReader()).plan(options, project).also {
                if (save && !interactive) {
                    if (dryRun) echo("(--dry-run: no guardé nada en testigo.yaml)") else persist(flagEdits(given, project, cwd), project)
                }
            }
        } catch (e: TestigoException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(e.exitCode.code)
        }

        // La política y la forma de los flows se revisan siempre, también en --dry-run, y antes de tocar el dispositivo o Maestro.
        val policy = PolicyReport(
            lint = FlowLinter().lint(plan.flows.map { it.path }, plan.project.config.app.packageName.takeIf { it != "auto" }),
            removedEnv = MaestroEnvironment.removedFrom(System.getenv()),
        )

        echo(RunPlanRenderer.render(plan, dryRun, policy))

        if (!policy.lint.passed) {
            val count = policy.lint.violations.size
            echo(
                "\nError: la política local-only bloqueó la corrida ($count ${if (count == 1) "violación" else "violaciones"}). " +
                    "Maestro no se ejecutó.",
                err = true,
            )
            throw ProgramResult(ExitCode.POLICY.code)
        }

        val problems = policy.lint.problems
        if (problems.isNotEmpty()) {
            val count = if (problems.size == 1) "un archivo de flows: corrígelo" else "${problems.size} archivos de flows: corrígelos"
            echo("\nError: Maestro no aceptaría $count como dice arriba. Maestro no se ejecutó.", err = true)
            throw ProgramResult(ExitCode.ENVIRONMENT.code)
        }

        if (dryRun) return

        if (plan.flows.isEmpty()) {
            echo("\nError: no hay flows que ejecutar: revisa --flows, --tags y --exclude-tags.", err = true)
            throw ProgramResult(ExitCode.ENVIRONMENT.code)
        }

        echo("\nPreflight")
        val preflightStep = Preflight(
            runner = ProcessCommandRunner(),
            sdk = { AndroidSdk.locate() },
            home = TestigoHome.dir(),
            confirm = ::confirm,
            progress = { echo("  $it") },
        )
        val (maestro, preflight) = try {
            preflightStep.checkArtifact(plan)
            val maestro = MaestroCli.locate()
            maestro to preflightStep.run(plan)
        } catch (e: TestigoException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(e.exitCode.code)
        }
        val installed = maestro.version()
        echo("  Maestro        ${installed ?: "versión desconocida"}")
        val pinned = plan.project.config.maestro.version
        val warnings = preflight.warnings +
            listOfNotNull(pinned?.takeIf { installed != null && it != installed }?.let { "Maestro $installed instalado, pero testigo.yaml fija $it." })
        val config = plan.project.config
        // El repo del artefacto (la app, con el branch del ticket) y, si no, el de la suite. Solo lectura local.
        val git = Git.read(
            listOf(preflight.app.artifact.path.parent, plan.project.dir),
            Regex(config.evidence.ticketPattern),
            ProcessCommandRunner(),
        )
        echo("  Git            ${git?.label ?: "no es un repo de git"}")
        warnings.forEach { echo("  ! $it") }

        echo("\nFlows")
        val recorder: ((String) -> Recorder)? = if (plan.record.value) {
            { serial -> ScreenRecorder(preflight.adb, serial, plan.videoBitrate.value, ProcessCommandRunner()) }
        } else {
            null
        }
        val logs: ((String) -> LogCollector)? = if (plan.logs.value) {
            { serial ->
                LogCapture(
                    adb = preflight.adb,
                    serial = serial,
                    packageName = preflight.app.packageName,
                    uid = preflight.appUid,
                    sdk = preflight.device.sdk,
                    runner = ProcessCommandRunner(),
                    keepRaw = plan.keepRaw.value,
                )
            }
        } else {
            null
        }
        val networkCapture: ((String) -> NetworkCollector)? = if (plan.network.value == NetworkMode.CHUCKER) {
            { serial ->
                ChuckerCapture(
                    adb = preflight.adb,
                    serial = serial,
                    packageName = preflight.app.packageName,
                    database = config.network.chuckerDb,
                    hosts = config.network.hosts,
                    slowMs = config.alerts.slowMs,
                    keepRaw = plan.keepRaw.value,
                    format = plan.networkFormat.value,
                    runner = ProcessCommandRunner(),
                )
            }
        } else {
            null
        }
        val rules = if (plan.redact.value) RedactRules.of(config.redact) else null
        val result = FlowExecutor(
            maestro,
            recorder = recorder,
            logs = logs,
            network = networkCapture,
            rules = rules,
            keepRaw = plan.keepRaw.value,
            progress = { echo("  $it") },
        ).run(
            plan.flows,
            preflight.device.serial,
            plan.env.values,
            plan.out.value,
            appId = preflight.app.packageName,
            runSuffix = Git.runSuffix(git),
        )
        val report = RunReport.write(
            result,
            RunContext(
                profile = plan.profile.value.cliName,
                device = preflight.device,
                app = preflight.app,
                maestroVersion = installed,
                git = git,
                options = options(plan),
                ci = plan.ci,
            ),
            cwd,
            rules?.let { Redactor(it, plan.env.values) } ?: Redactor.NONE,
        )
        echo(RunResultRenderer.render(result, cwd, report.summary))
        if (result.exitCode != ExitCode.OK) throw ProgramResult(result.exitCode.code)
    }

    /** Sin flags de selección, con `--ci` apagado y en una terminal: se pregunta qué ejecutar. */
    private fun interactive(): Boolean {
        if (ci || profile != null || flows.isNotEmpty() || tags != null || excludeTags != null || app != null) return false
        val info = terminal.terminalInfo
        return info.inputInteractive && info.outputInteractive
    }

    /**
     * Las preguntas del modo interactivo. Si la terminal no permite listas, sigue con lo de `testigo.yaml`. Al final ofrece
     * guardar en `testigo.yaml` lo que tiene lugar ahí (la app y el perfil), sin tocar el resto del archivo.
     */
    private fun ask(given: RunOptions, project: LoadedProject, cwd: Path): RunOptions {
        val questions = InteractiveRun(MordantAsker(terminal), YamlFlowHeaderReader(), cwd)
        val answers = try {
            questions.ask(given, project)
        } catch (e: TestigoException) {
            throw e
        } catch (e: Exception) {
            echo("! No pude abrir las listas de selección (${e.message}); sigo con lo de testigo.yaml.", err = true)
            return given
        }
        if (answers == null) cancelled()
        val edits = questions.saveable(answers, project)
        val file = project.file
        if (edits.isNotEmpty() && file != null) {
            val keep = save || (questions.askSave(edits, file.name) ?: cancelled())
            if (keep) savedConfig = persist(edits, project)
        }
        echo()
        return answers
    }

    private fun cancelled(): Nothing {
        echo("Cancelado: no se ejecutó nada.")
        throw ProgramResult(ExitCode.CANCELLED.code)
    }

    /** Lo de los flags que tiene lugar en `testigo.yaml` y todavía no está ahí. Las rutas quedan relativas a `testigo.yaml`. */
    private fun flagEdits(given: RunOptions, project: LoadedProject, cwd: Path): List<ConfigEdit> = buildList {
        given.app?.let { cwd.resolve(it).normalize() }?.takeIf { it.isRegularFile() }?.let {
            add(ConfigEdit.artifact(ConfigPaths.relative(project.dir, it)))
        }
        given.profile?.let { add(ConfigEdit.profile(it)) }
        given.flows.singleOrNull()?.let { cwd.resolve(it).normalize() }?.let { flows ->
            val relative = ConfigPaths.relative(project.dir, flows)
            add(ConfigEdit.flows(if (flows.isDirectory() && !relative.endsWith("/")) "$relative/" else relative))
        }
        given.videoBitrate?.let { add(ConfigEdit.videoBitrate(it)) }
        given.networkFormat?.let { add(ConfigEdit.networkFormat(it)) }
        given.redact?.let { add(ConfigEdit.redact(it)) }
        given.out?.let { add(ConfigEdit.evidenceDir(ConfigPaths.relative(project.dir, cwd.resolve(it).normalize()))) }
    }.filterNot { it.applied(project.config) }

    /** Las respuestas del modo interactivo ya quedaron en testigo.yaml. */
    private var savedConfig = false

    /** true si escribió testigo.yaml. Un problema al guardar no detiene la corrida. */
    private fun persist(edits: List<ConfigEdit>, project: LoadedProject): Boolean {
        val file = project.file
        if (file == null) {
            echo("! --save: no hay testigo.yaml donde guardar; créalo con testigo init.", err = true)
            return false
        }
        if (edits.isEmpty()) {
            echo("${file.name} ya tiene esos valores: no cambié nada.")
            return false
        }
        return try {
            ConfigEditor.save(file, edits)
            echo("✔ Guardado en ${file.name}: " + edits.joinToString(", ") { it.key })
            true
        } catch (e: TestigoException) {
            echo("! No pude guardar en ${file.name}: ${e.message}", err = true)
            false
        }
    }

    /** Opciones resueltas para `metadata.json`: de las variables, solo los nombres. */
    private fun options(plan: RunPlan): Map<String, Any?> = linkedMapOf(
        "profile" to plan.profile.value.cliName,
        "record" to plan.record.value,
        "video_bitrate_mbps" to plan.videoBitrate.value.takeIf { plan.record.value },
        "network" to plan.network.value.cliName,
        "network_format" to plan.networkFormat.value.cliName.takeIf { plan.network.value != NetworkMode.NONE },
        "logs" to plan.logs.value,
        "redact" to plan.redact.value,
        "keep_raw" to plan.keepRaw.value,
        "ci" to plan.ci,
        "tags" to plan.tags,
        "exclude_tags" to plan.excludeTags,
        "env_keys" to plan.env.values.keys.sorted(),
        "command" to EquivalentCommand.build(plan),
    )

    /** Pregunta antes de algo que borra datos. Sin terminal o con --ci, la respuesta es no. */
    private fun confirm(question: String): Boolean {
        if (ci || System.console() == null) return false
        echo("  $question [s/N] ", trailingNewline = false)
        return readlnOrNull()?.trim()?.lowercase() in setOf("s", "si", "sí", "y", "yes")
    }

    private fun List<String>?.cleaned(): List<String> =
        orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
