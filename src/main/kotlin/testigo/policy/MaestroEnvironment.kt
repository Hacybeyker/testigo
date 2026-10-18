package testigo.policy

/**
 * Entorno que el runner fija en cada proceso de Maestro, sin depender del shell del dev.
 * Comprobado con un firewall de salida (LuLu) sobre Maestro 2.11.0: con estas variables, Maestro no sale de la máquina.
 */
object MaestroEnvironment {
    val REQUIRED: Map<String, String> = linkedMapOf(
        // Sin analytics (PostHog).
        "MAESTRO_CLI_NO_ANALYTICS" to "1",
        // Oculta el aviso de versión nueva. Por sí sola no evita la consulta.
        "MAESTRO_DISABLE_UPDATE_CHECK" to "true",
        // La consulta de versión llama igual a api.copilot.mobile.dev al iniciar.
        // Con una URL local falla sin salir de la máquina.
        "MAESTRO_API_URL" to "http://127.0.0.1:9",
        // Oculta el aviso que invita a usar Maestro Cloud.
        "MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED" to "true",
    )

    /** Variables que habilitan envíos fuera de la máquina: la clave de los comandos de IA y la de Maestro Cloud. */
    private val REMOVED_PREFIXES = listOf("MAESTRO_CLI_AI_")
    private val REMOVED_NAMES = setOf("MAESTRO_CLOUD_API_KEY")

    fun isRemoved(name: String): Boolean = name in REMOVED_NAMES || REMOVED_PREFIXES.any { name.startsWith(it) }

    /** Modifica [env] en el lugar: quita las variables prohibidas y fija las obligatorias. */
    fun applyTo(env: MutableMap<String, String>) {
        env.keys.removeAll { isRemoved(it) }
        env.putAll(REQUIRED)
    }

    /** Variables de [current] que el runner quitará. Solo nombres, para avisar en `--dry-run`. */
    fun removedFrom(current: Map<String, String>): List<String> = current.keys.filter { isRemoved(it) }.sorted()
}
