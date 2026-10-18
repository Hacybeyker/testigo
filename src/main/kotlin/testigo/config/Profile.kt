package testigo.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Conjunto de opciones que se activan de una sola vez con `--profile`.
 * Un flag explícito (`--record`, `--network`, `--logs`) siempre gana sobre el perfil.
 */
@Serializable
enum class Profile(
    val cliName: String,
    val record: Boolean,
    /** Si el perfil pide capturar red. El cómo (chucker, none) lo decide `network.capture` en testigo.yaml. */
    val captureNetwork: Boolean,
    val logs: Boolean,
    val purpose: String,
) {
    @SerialName("smoke")
    SMOKE("smoke", record = false, captureNetwork = false, logs = false, purpose = "verificación rápida antes de un commit"),

    @SerialName("dev")
    DEV("dev", record = false, captureNetwork = true, logs = true, purpose = "depurar un flow mientras se desarrolla"),

    @SerialName("evidencia")
    EVIDENCIA("evidencia", record = true, captureNetwork = true, logs = true, purpose = "evidencia completa para el PR o el ticket"),
    ;

    companion object {
        val DEFAULT = DEV
    }
}

/** En qué formato se escribe la red de cada flow. */
@Serializable
enum class NetworkFormat(val cliName: String, val label: String) {
    /** `network.txt`: cada llamada en texto plano, con el JSON formateado. Se lee sin herramientas. */
    @SerialName("txt")
    TXT("txt", "texto"),

    /** `network.har`: para Chrome DevTools, Charles o Proxyman. */
    @SerialName("har")
    HAR("har", "HAR"),

    @SerialName("ambos")
    BOTH("ambos", "texto y HAR"),
    ;

    val text: Boolean get() = this != HAR
    val har: Boolean get() = this != TXT

    companion object {
        val DEFAULT = TXT
    }
}

/** Cómo se captura el tráfico de red. `mitm` llega en v2. */
@Serializable
enum class NetworkMode(val cliName: String) {
    @SerialName("chucker")
    CHUCKER("chucker"),

    @SerialName("none")
    NONE("none"),
}
