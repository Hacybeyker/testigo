package testigo.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Modelo de testigo.yaml. Todo lo específico de cada app vive aquí; el runner no conoce ningún negocio.
 * Los campos que el usuario puede sobrescribir con flags son nullables para saber de dónde salió cada valor.
 */
@Serializable
data class ProjectConfig(
    val version: Int = SUPPORTED_VERSION,
    val app: AppConfig = AppConfig(),
    /** Carpeta o archivo de flows, relativo a testigo.yaml. Por defecto, [DEFAULT_FLOWS]. */
    val flows: String? = null,
    val maestro: MaestroConfig = MaestroConfig(),
    val defaults: DefaultsConfig = DefaultsConfig(),
    val network: NetworkConfig = NetworkConfig(),
    val video: VideoConfig = VideoConfig(),
    val alerts: AlertsConfig = AlertsConfig(),
    val redact: RedactConfig = RedactConfig(),
    val evidence: EvidenceConfig = EvidenceConfig(),
) {
    companion object {
        const val SUPPORTED_VERSION = 1
        const val DEFAULT_FLOWS = "flows/"
    }
}

@Serializable
data class AppConfig(
    /** Ruta al .apk o .aab, relativa a testigo.yaml. */
    val artifact: String? = null,
    /** `auto`: el package se lee del artefacto (paso 3). */
    @SerialName("package") val packageName: String = "auto",
    /** Opcional, solo para AAB. Sin esto, bundletool firma con la debug key. */
    val signing: SigningConfig? = null,
)

@Serializable
data class SigningConfig(
    val keystore: String,
    val alias: String,
    /** Nombre de la variable de entorno con la clave. La clave nunca va en el YAML. */
    @SerialName("password_env") val passwordEnv: String,
)

@Serializable
data class MaestroConfig(
    /** Versión de Maestro fijada para el equipo; `doctor` avisará si la instalada difiere. */
    val version: String? = null,
)

@Serializable
data class DefaultsConfig(
    val profile: Profile? = null,
)

@Serializable
data class NetworkConfig(
    /** Cómo capturar la red cuando el perfil la pide. `none` = la app no trae Chucker. */
    val capture: NetworkMode? = null,
    /** `txt` (por defecto), `har` o `ambos`. */
    val format: NetworkFormat? = null,
    /** `auto`: se detecta listando databases/ con run-as (paso 7). */
    @SerialName("chucker_db") val chuckerDb: String = "auto",
    /** Dominios del backend que entran al resumen. */
    val hosts: List<String> = emptyList(),
)

@Serializable
data class VideoConfig(
    /** Calidad del video en Mbps, de [MIN_MBPS] a [MAX_MBPS]. Por defecto, [DEFAULT_MBPS]. */
    @SerialName("bitrate_mbps") val bitrateMbps: Int? = null,
) {
    companion object {
        const val DEFAULT_MBPS = 4
        const val MIN_MBPS = 4
        const val MAX_MBPS = 8
    }
}

@Serializable
data class AlertsConfig(
    /** Llamada lenta a partir de este tiempo, en milisegundos. */
    @SerialName("slow_ms") val slowMs: Long = 3_000,
)

@Serializable
enum class RedactPreset(val cliName: String) {
    @SerialName("base")
    BASE("base"),

    @SerialName("pii")
    PII("pii"),

    @SerialName("payments")
    PAYMENTS("payments"),
}

/** Qué se enmascara en la evidencia. El contenido de cada preset está en `redact/RedactRules.kt`. */
@Serializable
data class RedactConfig(
    /**
     * Sanitizar la evidencia y correr el leak scan. Por defecto, no: la evidencia trae los datos exactos para
     * replicar los requests en el backend. `--redact` / `--no-redact` lo cambia para una corrida.
     */
    val enabled: Boolean? = null,
    /** Con la sanitización activa, todos los presets por defecto. */
    val presets: List<RedactPreset> = RedactPreset.entries.toList(),
    /** Headers propios del proyecto; se suman a los de los presets. */
    val headers: List<String> = emptyList(),
    /** Claves propias del dominio (número de póliza, ID de cliente); se buscan a cualquier profundidad. */
    @SerialName("json_keys") val jsonKeys: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_ENABLED = false
    }
}

@Serializable
data class EvidenceConfig(
    /** Carpeta de evidencia, relativa a testigo.yaml. Por defecto, [DEFAULT_DIR]. */
    val dir: String? = null,
    @SerialName("ticket_pattern") val ticketPattern: String = "[A-Z]+-\\d+",
    @SerialName("keep_raw") val keepRaw: Boolean = false,
) {
    companion object {
        const val DEFAULT_DIR = "evidence/"
    }
}

/** testigo.yaml ya cargado, con la carpeta desde la que se resuelven sus rutas relativas. */
data class LoadedProject(
    val config: ProjectConfig,
    /** null si no se encontró testigo.yaml: se usan los valores por defecto. */
    val file: Path?,
    val dir: Path,
)
