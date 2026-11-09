package testigo.maestro

import testigo.util.Json

/**
 * Un paso de un flow, tal como lo registra Maestro en `commands.json`: cuándo empezó (hora de esta máquina, en
 * milisegundos) y cuánto tardó. [depth] es 0 en el flow y 1 o más dentro de un `runFlow`.
 */
data class Step(
    val sequence: Int,
    val depth: Int,
    /** El comando como se escribe en el YAML: `tapOn`, `assertVisible`, `extendedWaitUntil`… */
    val command: String,
    /** El `label` del paso, si lo tiene: es la forma de encontrarlo en la evidencia. */
    val label: String?,
    /** El selector (`"Ingresar"`, `id=login_submit`), el subflow o el nombre de la captura. Nunca un texto escrito con `inputText`. */
    val detail: String?,
    val status: String,
    val startMs: Long,
    val durationMs: Long,
) {
    val endMs: Long get() = startMs + durationMs
}

/** Lee `commands.json` de Maestro 2.x (`--test-output-dir`). */
object StepTimeline {
    /** Maestro los agrega al empezar; no son pasos del flow. */
    private val INTERNAL = setOf("defineVariablesCommand", "applyConfigurationCommand")

    /** Vacío si el archivo no es de Maestro 2.x o no se puede leer: la evidencia sigue sin la vista por paso. */
    fun parse(json: String): List<Step> {
        val entries = runCatching { Json.parse(json) }.getOrNull() as? List<*> ?: return emptyList()
        return entries.mapNotNull { entry ->
            val item = entry as? Map<*, *> ?: return@mapNotNull null
            val command = item["command"] as? Map<*, *> ?: return@mapNotNull null
            val metadata = item["metadata"] as? Map<*, *> ?: return@mapNotNull null
            val key = command.keys.singleOrNull() as? String ?: return@mapNotNull null
            if (key in INTERNAL) return@mapNotNull null
            val body = command[key] as? Map<*, *> ?: return@mapNotNull null
            val start = (metadata["timestamp"] as? Number)?.toLong() ?: return@mapNotNull null
            Step(
                sequence = (metadata["sequenceNumber"] as? Number)?.toInt() ?: return@mapNotNull null,
                depth = (metadata["depth"] as? Number)?.toInt() ?: 0,
                command = name(key, body),
                label = (body["label"] as? String)?.takeIf { it.isNotBlank() },
                detail = detail(name(key, body), body),
                status = metadata["status"] as? String ?: "",
                startMs = start,
                durationMs = (metadata["duration"] as? Number)?.toLong() ?: 0,
            )
        }.sortedBy { it.sequence }
    }

    /** `extendedWaitUntil` y `assertVisible` son el mismo `assertConditionCommand`: los distingue el `timeout`. */
    private fun name(key: String, body: Map<*, *>): String = when (key) {
        "tapOnElement" -> "tapOn"
        "backPressCommand" -> "back"
        "assertConditionCommand" -> {
            val condition = body["condition"] as? Map<*, *>
            when {
                body["timeout"] != null -> "extendedWaitUntil"
                condition?.containsKey("notVisible") == true -> "assertNotVisible"
                condition?.containsKey("visible") == true -> "assertVisible"
                else -> "assert"
            }
        }
        else -> key.removeSuffix("Command").replaceFirstChar { it.lowercase() }
    }

    private fun detail(name: String, body: Map<*, *>): String? {
        selector(body)?.let { return it }
        return when (name) {
            "runFlow" -> (body["sourceDescription"] as? String)?.substringAfterLast('/')
            "takeScreenshot" -> body["path"] as? String
            else -> null
        }
    }

    private fun selector(body: Map<*, *>): String? {
        val condition = body["condition"] as? Map<*, *>
        val candidates = listOf(body["selector"], body["element"], condition?.get("visible") ?: condition?.get("notVisible"))
        for (candidate in candidates) {
            val selector = candidate as? Map<*, *> ?: continue
            (selector["textRegex"] as? String)?.let { return "\"$it\"" }
            (selector["idRegex"] as? String)?.let { return "id=$it" }
        }
        return null
    }
}
