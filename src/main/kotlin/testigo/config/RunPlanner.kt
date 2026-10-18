package testigo.config

import testigo.TestigoException
import testigo.artifact.Artifact
import testigo.artifact.ArtifactFormat
import testigo.maestro.FlowDiscovery
import testigo.maestro.FlowHeaderReader
import testigo.util.displayFrom
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Combina flags, perfil y testigo.yaml en un [RunPlan].
 *
 * Precedencia: flag explícito > perfil > testigo.yaml > valor por defecto del runner.
 * El perfil sale de `--profile`, si no de `defaults.profile`, si no es `dev`.
 * Las rutas de los flags son relativas a la carpeta actual; las de testigo.yaml, a la carpeta de testigo.yaml.
 */
class RunPlanner(
    private val cwd: Path,
    private val headerReader: FlowHeaderReader,
) {

    fun plan(options: RunOptions, project: LoadedProject): RunPlan {
        val config = project.config
        val warnings = mutableListOf<String>()
        if (project.file == null) {
            warnings.add("No encontré ${ProjectLocator.FILE_NAME}: se usan los valores por defecto.")
        } else if (config.maestro.version == null) {
            warnings.add("No hay versión de Maestro fijada (maestro.version en ${ProjectLocator.FILE_NAME}).")
        }

        val profile = resolveProfile(options.profile, config.defaults.profile)
        val record = options.record?.let { Resolved(it, Source.FLAG) } ?: Resolved(profile.value.record, Source.PROFILE)
        val videoBitrate = resolveVideoBitrate(options.videoBitrate, config.video.bitrateMbps)
        val networkFormat = when {
            options.networkFormat != null -> Resolved(options.networkFormat, Source.FLAG)
            config.network.format != null -> Resolved(config.network.format, Source.PROJECT)
            else -> Resolved(NetworkFormat.DEFAULT, Source.DEFAULT)
        }
        val logs = options.logs?.let { Resolved(it, Source.FLAG) } ?: Resolved(profile.value.logs, Source.PROFILE)
        val network = options.network?.let { Resolved(it, Source.FLAG) }
            ?: networkFor(profile.value, config.network.capture)

        val artifact = resolveArtifact(options.app, config.app.artifact, project.dir)
        if (!artifact.value.path.exists()) {
            warnings.add("La app todavía no existe: ${artifact.value.path.displayFrom(cwd)}")
        }

        val flowRoots = resolveFlowRoots(options.flows, config.flows, project)
        val discovery = FlowDiscovery.discover(flowRoots.value, headerReader)
        discovery.missingRoots.forEach { warnings.add("No existe la ruta de flows: ${it.displayFrom(cwd)}") }
        discovery.unreadable.forEach { (path, reason) ->
            warnings.add("No pude leer el header de ${path.displayFrom(cwd)}: $reason")
        }
        val selected = FlowDiscovery.filterByTags(discovery.flows, options.tags, options.excludeTags)
        if (discovery.flows.isEmpty() && discovery.missingRoots.isEmpty()) {
            warnings.add("No encontré flows (.yaml) en: ${flowRoots.value.joinToString { it.displayFrom(cwd) }}")
        } else if (discovery.flows.isNotEmpty() && selected.isEmpty()) {
            warnings.add("Ningún flow coincide con los tags indicados.")
        }

        val keepRaw = when {
            options.keepRaw -> Resolved(true, Source.FLAG)
            config.evidence.keepRaw -> Resolved(true, Source.PROJECT)
            else -> Resolved(false, Source.DEFAULT)
        }
        if (keepRaw.value) {
            warnings.add("Se conservarán los datos crudos sin sanitizar: úsalo solo para depurar.")
        }
        val redact = when {
            options.redact != null -> Resolved(options.redact, Source.FLAG)
            config.redact.enabled != null -> Resolved(config.redact.enabled, Source.PROJECT)
            else -> Resolved(RedactConfig.DEFAULT_ENABLED, Source.DEFAULT)
        }
        if (redact.value && RedactPreset.BASE !in config.redact.presets) {
            warnings.add(
                "redact.presets no incluye base: tokens y contraseñas no se enmascaran. " +
                    "Si aparece un JWT, un bearer o una clave privada, la corrida termina con código 4.",
            )
        }

        return RunPlan(
            cwd = cwd,
            project = project,
            profile = profile,
            artifact = artifact,
            flowRoots = flowRoots,
            discoveredFlows = discovery.flows,
            flows = selected,
            tags = options.tags,
            excludeTags = options.excludeTags,
            record = record,
            videoBitrate = videoBitrate,
            network = network,
            networkFormat = networkFormat,
            logs = logs,
            device = Resolved(options.device, if (options.device != null) Source.FLAG else Source.DEFAULT),
            env = resolveEnv(options, project),
            out = resolveOut(options.out, config.evidence.dir, project),
            ci = options.ci,
            keepRaw = keepRaw,
            redact = redact,
            warnings = warnings,
        )
    }

    private fun resolveProfile(flag: Profile?, fromProject: Profile?): Resolved<Profile> = when {
        flag != null -> Resolved(flag, Source.FLAG)
        fromProject != null -> Resolved(fromProject, Source.PROJECT)
        else -> Resolved(Profile.DEFAULT, Source.DEFAULT)
    }

    private fun resolveVideoBitrate(flag: Int?, fromProject: Int?): Resolved<Int> {
        if (flag != null && flag !in VideoConfig.MIN_MBPS..VideoConfig.MAX_MBPS) {
            throw TestigoException("--video-bitrate debe estar entre ${VideoConfig.MIN_MBPS} y ${VideoConfig.MAX_MBPS} (Mbps): $flag")
        }
        return when {
            flag != null -> Resolved(flag, Source.FLAG)
            fromProject != null -> Resolved(fromProject, Source.PROJECT)
            else -> Resolved(VideoConfig.DEFAULT_MBPS, Source.DEFAULT)
        }
    }

    private fun resolveArtifact(flag: Path?, fromProject: String?, projectDir: Path): Resolved<Artifact> {
        val path: Path
        val source: Source
        when {
            flag != null -> {
                path = cwd.resolve(flag).normalize()
                source = Source.FLAG
            }
            fromProject != null -> {
                path = projectDir.resolve(fromProject).normalize()
                source = Source.PROJECT
            }
            else -> throw TestigoException(
                "Falta la app a probar: usa --app <ruta al .apk o .aab> o define app.artifact en ${ProjectLocator.FILE_NAME}.",
            )
        }
        val format = ArtifactFormat.of(path.fileName.toString())
            ?: throw TestigoException("La app debe ser un .apk o un .aab: ${path.displayFrom(cwd)}")
        return Resolved(Artifact(path, format), source)
    }

    private fun resolveFlowRoots(flags: List<Path>, fromProject: String?, project: LoadedProject): Resolved<List<Path>> =
        when {
            flags.isNotEmpty() -> Resolved(flags.map { cwd.resolve(it).normalize() }, Source.FLAG)
            fromProject != null -> Resolved(listOf(project.dir.resolve(fromProject).normalize()), Source.PROJECT)
            else -> Resolved(listOf(project.dir.resolve(ProjectConfig.DEFAULT_FLOWS).normalize()), Source.DEFAULT)
        }

    private fun resolveOut(flag: Path?, fromProject: String?, project: LoadedProject): Resolved<Path> = when {
        flag != null -> Resolved(cwd.resolve(flag).normalize(), Source.FLAG)
        fromProject != null -> Resolved(project.dir.resolve(fromProject).normalize(), Source.PROJECT)
        else -> Resolved(project.dir.resolve(EvidenceConfig.DEFAULT_DIR).normalize(), Source.DEFAULT)
    }

    /** `--env-file` explícito debe existir; si no, se usa `.env.testigo` junto a testigo.yaml cuando existe. */
    private fun resolveEnv(options: RunOptions, project: LoadedProject): EnvVars {
        val file: Path?
        val source: Source?
        if (options.envFile != null) {
            file = cwd.resolve(options.envFile).normalize()
            source = Source.FLAG
            if (!file.isRegularFile()) throw TestigoException("No existe el archivo de variables: ${file.displayFrom(cwd)}")
        } else {
            val candidate = project.dir.resolve(DEFAULT_ENV_FILE)
            file = candidate.takeIf { it.isRegularFile() }
            source = if (file != null) Source.DEFAULT else null
        }

        val fromFile = file?.let { EnvFile.parse(it.readText(), it.displayFrom(cwd)) }.orEmpty()
        options.env.keys.firstOrNull { !EnvFile.isValidKey(it) }?.let {
            throw TestigoException("Nombre de variable inválido en --env: $it")
        }
        return EnvVars(
            values = fromFile + options.env,
            file = file,
            fileSource = source,
            fileKeys = fromFile.keys.toList(),
            inlineKeys = options.env.keys.toList(),
        )
    }

    companion object {
        const val DEFAULT_ENV_FILE = ".env.testigo"
    }
}
