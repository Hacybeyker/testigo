package testigo.network

import testigo.maestro.Step
import kotlin.math.abs

/** Una llamada de red tal como la necesita la vista por paso: sin headers ni bodies, y con la hora del dispositivo. */
data class NetworkCall(
    val id: Long,
    val method: String,
    /** Sin query string. */
    val path: String,
    val status: Int?,
    val durationMs: Long?,
    /** Hora del dispositivo en que salió el request, en milisegundos. */
    val requestMs: Long,
    val failed: Boolean,
)

/**
 * Lo que el dispositivo marca de más (o de menos) respecto de esta máquina, medido al empezar el flow. Los pasos de
 * Maestro llevan la hora de la máquina y Chucker, la del dispositivo: para cruzarlas hay que restar [deviceMinusHostMs].
 * [uncertaintyMs] es lo que pudo tardar la medición (la mitad del viaje de ida y vuelta de adb).
 */
data class ClockOffset(val deviceMinusHostMs: Long, val uncertaintyMs: Long)

/**
 * Atribuye cada llamada de red al paso del flow que estaba corriendo cuando salió, y lo escribe en `network-steps.txt`.
 * Sirve para contar las llamadas desde un toque concreto sin partir el flow: Maestro no corre comandos de shell a mitad de
 * camino, pero sí deja la hora de cada paso.
 *
 * - La llamada es del paso más profundo que la contiene (el del subflow, no su `runFlow`).
 * - Una llamada entre dos pasos, o pegada al borde por el error de los relojes, va al más cercano si está a menos de
 *   la incertidumbre más [SLACK_MS]; si no, a «Fuera de los pasos».
 * - Un toque que dispara un request lo hace durante el propio paso (Maestro espera a que la pantalla se asiente), así
 *   que la llamada queda en el paso que la provocó. Un request que sale mucho después cae en el paso que corra entonces.
 */
object StepNetwork {
    private const val SLACK_MS = 500L

    /** null si no hay pasos o llamadas que cruzar. */
    fun render(flow: String, steps: List<Step>, calls: List<NetworkCall>, clock: ClockOffset?): String? {
        if (steps.isEmpty() || calls.isEmpty()) return null
        val offset = clock?.deviceMinusHostMs ?: 0
        val slack = (clock?.uncertaintyMs ?: 0) + SLACK_MS
        val owned = calls.sortedBy { it.requestMs }.groupBy { call -> owner(steps, call.requestMs - offset, slack) }
        val origin = steps.minOf { it.startMs }

        return buildString {
            appendLine("# Llamadas de red por paso · $flow")
            appendLine("# Cada paso de Maestro (commands.json) con las llamadas de Chucker que salieron mientras corría.")
            appendLine("# " + clockNote(clock))
            appendLine("# Para ubicar un paso en el flow, ponle `label` en el YAML: aparece aquí.")
            appendLine()
            steps.forEach { step ->
                val mine = owned[step].orEmpty()
                val title = listOfNotNull(step.command, step.detail, step.label?.let { "[$it]" }).joinToString(" ")
                val count = if (mine.isEmpty()) "sin llamadas" else "${mine.size} ${if (mine.size == 1) "llamada" else "llamadas"}"
                val failed = if (step.status == "FAILED") " · FALLÓ" else ""
                appendLine("${"  ".repeat(step.depth)}paso ${step.sequence} · $title · +${seconds(step.startMs - origin)} · ${seconds(step.durationMs)}$failed · $count")
                mine.forEach { call -> appendLine("${"  ".repeat(step.depth + 1)}${line(call, call.requestMs - offset - step.startMs)}") }
            }
            val outside = owned[null].orEmpty()
            if (outside.isNotEmpty()) {
                appendLine()
                appendLine("Fuera de los pasos (${outside.size})")
                outside.forEach { call -> appendLine("  ${line(call, call.requestMs - offset - origin)}") }
            }
        }
    }

    /** El paso al que pertenece la llamada que salió en [hostMs] (hora de esta máquina); null si no es de ninguno. */
    internal fun owner(steps: List<Step>, hostMs: Long, slackMs: Long): Step? {
        steps.filter { hostMs in it.startMs..it.endMs }
            .maxWithOrNull(compareBy<Step>({ it.depth }, { it.sequence }))
            ?.let { return it }
        return steps.map { it to distance(it, hostMs) }
            .filter { (_, distance) -> distance <= slackMs }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    private fun distance(step: Step, hostMs: Long): Long = if (hostMs < step.startMs) step.startMs - hostMs else abs(hostMs - step.endMs)

    private fun line(call: NetworkCall, afterMs: Long): String {
        val outcome = call.status?.toString() ?: "sin respuesta"
        val time = call.durationMs?.let { " · $it ms" }.orEmpty()
        return "#${call.id} ${call.method} ${call.path.ifEmpty { "/" }} → $outcome$time · ${if (afterMs >= 0) "+" else ""}$afterMs ms${if (call.failed) " · error" else ""}"
    }

    private fun clockNote(clock: ClockOffset?): String =
        if (clock == null) {
            "No se pudo medir el desfase entre el reloj del dispositivo y el de la máquina: se supone 0, y una llamada puede caer en un paso vecino."
        } else {
            val sign = if (clock.deviceMinusHostMs >= 0) "+" else ""
            "Reloj del dispositivo: $sign${clock.deviceMinusHostMs} ms respecto del de la máquina (± ${clock.uncertaintyMs} ms). " +
                "Una llamada pegada al borde de un paso puede caer en el vecino."
        }

    private fun seconds(millis: Long): String = "%.1f s".format(java.util.Locale.ROOT, millis / 1000.0)
}
