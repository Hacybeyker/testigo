package testigo.config

import testigo.artifact.Artifact
import testigo.maestro.FlowFile
import java.nio.file.Path

/** De dónde salió un valor resuelto. Se muestra en `--dry-run` para que la precedencia sea visible. */
enum class Source(val label: String) {
    FLAG("flag"),
    PROFILE("perfil"),
    PROJECT("testigo.yaml"),
    DEFAULT("por defecto"),
}

data class Resolved<out T>(val value: T, val source: Source)

/** Opciones tal como llegaron por la línea de comandos. null = no se pasó el flag. */
data class RunOptions(
    val config: Path? = null,
    val profile: Profile? = null,
    val app: Path? = null,
    val flows: List<Path> = emptyList(),
    val tags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val record: Boolean? = null,
    val videoBitrate: Int? = null,
    val network: NetworkMode? = null,
    val networkFormat: NetworkFormat? = null,
    val logs: Boolean? = null,
    val device: String? = null,
    val env: Map<String, String> = emptyMap(),
    val envFile: Path? = null,
    val out: Path? = null,
    val ci: Boolean = false,
    val dryRun: Boolean = false,
    val keepRaw: Boolean = false,
    /** `--redact` / `--no-redact`. */
    val redact: Boolean? = null,
    /** `--repeat`: cuántas veces corre cada flow. */
    val repeat: Int? = null,
) {
    override fun toString(): String = "RunOptions(envKeys=${env.keys}, ...)"
}

/**
 * Variables que se pasarán a los flows. No es una data class a propósito:
 * su toString() nunca muestra valores, porque suelen ser credenciales.
 */
class EnvVars(
    val values: Map<String, String>,
    val file: Path?,
    val fileSource: Source?,
    val fileKeys: List<String>,
    val inlineKeys: List<String>,
) {
    override fun toString(): String = "EnvVars(keys=${values.keys})"
}

/** Todo lo que necesita una corrida, ya resuelto. Lo que `--dry-run` imprime. */
data class RunPlan(
    val cwd: Path,
    val project: LoadedProject,
    val profile: Resolved<Profile>,
    val artifact: Resolved<Artifact>,
    val flowRoots: Resolved<List<Path>>,
    val discoveredFlows: List<FlowFile>,
    val flows: List<FlowFile>,
    val tags: List<String>,
    val excludeTags: List<String>,
    val record: Resolved<Boolean>,
    /** Mbps de la grabación; solo importa si [record] es true. */
    val videoBitrate: Resolved<Int>,
    val network: Resolved<NetworkMode>,
    /** Solo importa si [network] captura. */
    val networkFormat: Resolved<NetworkFormat>,
    val logs: Resolved<Boolean>,
    val device: Resolved<String?>,
    val env: EnvVars,
    val out: Resolved<Path>,
    val ci: Boolean,
    val keepRaw: Resolved<Boolean>,
    /** Sanitización y leak scan. */
    val redact: Resolved<Boolean>,
    val warnings: List<String>,
    /** Cuántas veces corre la lista de flows, en rondas. 1 es una corrida normal. */
    val repeat: Int = 1,
)

/** Límites de `--repeat`: más de [MAX] corridas seguidas suele ser un error de tipeo. */
object Repeat {
    const val MIN = 1
    const val MAX = 20
}

/**
 * Red que corresponde a un perfil cuando no se pasa `--network`:
 * si el perfil no la pide, no se captura; si la pide, se usa el método de testigo.yaml (Chucker por defecto).
 */
fun networkFor(profile: Profile, projectCapture: NetworkMode?): Resolved<NetworkMode> = when {
    !profile.captureNetwork -> Resolved(NetworkMode.NONE, Source.PROFILE)
    projectCapture != null -> Resolved(projectCapture, Source.PROJECT)
    else -> Resolved(NetworkMode.CHUCKER, Source.PROFILE)
}
