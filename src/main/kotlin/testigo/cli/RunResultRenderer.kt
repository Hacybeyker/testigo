package testigo.cli

import testigo.maestro.FlowExecutor
import testigo.maestro.RunResult
import testigo.redact.EvidenceStatus
import testigo.util.displayFrom
import java.nio.file.Path

/** Tabla final de `testigo run`: un renglón por flow, con su reporte y, si falló, el motivo. */
object RunResultRenderer {
    private const val MAX_HIGHLIGHTS = 5
    private const val MAX_LEAKS = 5

    /** [summary]: el `summary.md` de la corrida, para copiarlo al PR o al ticket. */
    fun render(result: RunResult, cwd: Path, summary: Path? = null): String = buildString {
        appendLine()
        appendLine("Resultado: ${result.passed} de ${result.flows.size} flows pasaron · ${FlowExecutor.format(result.duration)}")
        val nameWidth = result.flows.maxOfOrNull { it.name.length } ?: 0
        result.flows.forEach { flow ->
            append("  ${flow.status.symbol} ${flow.name.padEnd(nameWidth)}  ${FlowExecutor.format(flow.duration).padStart(9)}")
            flow.report?.let { append("  ").append(it.displayFrom(cwd)) }
            appendLine()
            flow.reason?.lines()?.forEach { appendLine("      $it") }
            flow.crashFile?.let { appendLine("      crash: ${it.displayFrom(cwd)}") }
            flow.network?.let { network ->
                network.summary?.let { s ->
                    appendLine("      red: ${s.requests} llamadas · ${s.failed} con error · ${s.slow} lentas (≥ ${s.slowMs} ms)")
                    network.highlights.take(MAX_HIGHLIGHTS).forEach { appendLine("        ! $it") }
                    if (network.highlights.size > MAX_HIGHLIGHTS) {
                        appendLine("        … y ${network.highlights.size - MAX_HIGHLIGHTS} más")
                    }
                    listOfNotNull(network.text, network.har).forEach { appendLine("        ${it.displayFrom(cwd)}") }
                }
                network.note?.let { appendLine("      ! red: $it") }
            }
            flow.videos.forEach { appendLine("      video: ${it.displayFrom(cwd)}") }
            flow.videoNote?.let { appendLine("      ! $it") }
            if (flow.leaks.isNotEmpty()) {
                val count = flow.leaks.size
                appendLine("      ! leak scan: $count ${if (count == 1) "dato" else "datos"} sin enmascarar")
                // Solo dónde y qué regla: nunca el valor.
                flow.leaks.take(MAX_LEAKS).forEach { leak ->
                    val file = leak.file.toString().replace('\\', '/')
                    appendLine("        $file:${leak.line}  ${leak.rule}")
                }
                if (count > MAX_LEAKS) appendLine("        … y ${count - MAX_LEAKS} más")
            }
        }
        appendLine("Evidencia: ${result.dir.displayFrom(cwd)}")
        summary?.let { appendLine("Resumen:   ${it.displayFrom(cwd)}") }
        if (result.evidenceStatus == EvidenceStatus.NO_APTA) {
            appendLine("! Evidencia NO APTA: el leak scan encontró datos sin enmascarar. No la compartas; revisa redact en testigo.yaml.")
        }
        if (result.keepRaw) appendLine("! raw/ de cada flow tiene datos sin sanitizar (--keep-raw): no compartas esas carpetas.")
    }.trimEnd()
}
