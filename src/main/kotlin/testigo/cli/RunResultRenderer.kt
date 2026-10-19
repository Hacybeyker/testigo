package testigo.cli

import testigo.maestro.FlowExecutor
import testigo.maestro.FlowResult
import testigo.maestro.FlowStatus
import testigo.maestro.FlowStability
import testigo.maestro.RunResult
import testigo.redact.EvidenceStatus
import testigo.util.displayFrom
import java.nio.file.Path

/**
 * Tabla final de `testigo run`: un renglón por flow, con su reporte y, si falló, el motivo. Con `--repeat`, un renglón por
 * flow con cuántas veces pasó, y el detalle solo de las corridas que fallaron.
 */
object RunResultRenderer {
    private const val MAX_HIGHLIGHTS = 5
    private const val MAX_LEAKS = 5

    /** [summary]: el `summary.md` de la corrida, para copiarlo al PR o al ticket. */
    fun render(result: RunResult, cwd: Path, summary: Path? = null): String = buildString {
        appendLine()
        if (result.repeat > 1) repeated(result, cwd) else single(result, cwd)
        appendLine("Evidencia: ${result.dir.displayFrom(cwd)}")
        summary?.let { appendLine("Resumen:   ${it.displayFrom(cwd)}") }
        if (result.evidenceStatus == EvidenceStatus.NO_APTA) {
            appendLine("! Evidencia NO APTA: el leak scan encontró datos sin enmascarar. No la compartas; revisa redact en testigo.yaml.")
        }
        if (result.keepRaw) appendLine("! raw/ de cada flow tiene datos sin sanitizar (--keep-raw): no compartas esas carpetas.")
    }.trimEnd()

    private fun StringBuilder.single(result: RunResult, cwd: Path) {
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
            leaks(flow, "      ")
        }
    }

    /** `--repeat`: estable, intermitente o falla siempre, y de cada corrida que falló, el motivo y dónde mirar. */
    private fun StringBuilder.repeated(result: RunResult, cwd: Path) {
        val stability = result.stability
        val flaky = stability.count { it.flaky }
        val header = listOfNotNull(
            "${result.passed} de ${result.flows.size} corridas pasaron",
            "${stability.size} ${if (stability.size == 1) "flow" else "flows"} × ${result.repeat}",
            if (flaky > 0) "$flaky ${if (flaky == 1) "intermitente" else "intermitentes"}" else null,
            FlowExecutor.format(result.duration),
        )
        appendLine("Resultado: ${header.joinToString(" · ")}")
        val nameWidth = stability.maxOfOrNull { it.name.length } ?: 0
        val labelWidth = stability.maxOfOrNull { it.label.length } ?: 0
        stability.forEach { flow ->
            val count = "${flow.passed}/${flow.runs.size}"
            val mean = "media ${FlowExecutor.format(flow.meanDuration)}"
            appendLine("  ${symbol(flow)} ${flow.name.padEnd(nameWidth)}  ${count.padStart(5)}  ${flow.label.padEnd(labelWidth)}  $mean")
            flow.runs.forEach { run -> attempt(run, cwd) }
        }
    }

    /** Lo de una corrida que vale mirar: si falló, el motivo, el reporte, el crash y el video; y siempre, los leaks. */
    private fun StringBuilder.attempt(run: FlowResult, cwd: Path) {
        val failed = run.status == FlowStatus.FAILED
        if (!failed && run.leaks.isEmpty()) return
        appendLine("      #${run.attempt}  ${if (failed) run.status.label else "pasó"} · ${FlowExecutor.format(run.duration)}")
        if (failed) {
            run.reason?.lines()?.forEach { appendLine("          $it") }
            run.report?.let { appendLine("          ${it.displayFrom(cwd)}") }
            run.crashFile?.let { appendLine("          crash: ${it.displayFrom(cwd)}") }
            run.videos.forEach { appendLine("          video: ${it.displayFrom(cwd)}") }
        }
        leaks(run, "          ")
    }

    private fun StringBuilder.leaks(flow: FlowResult, indent: String) {
        if (flow.leaks.isEmpty()) return
        val count = flow.leaks.size
        appendLine("$indent! leak scan: $count ${if (count == 1) "dato" else "datos"} sin enmascarar")
        // Solo dónde y qué regla: nunca el valor.
        flow.leaks.take(MAX_LEAKS).forEach { leak ->
            val file = leak.file.toString().replace('\\', '/')
            appendLine("$indent  $file:${leak.line}  ${leak.rule}")
        }
        if (count > MAX_LEAKS) appendLine("$indent  … y ${count - MAX_LEAKS} más")
    }

    private fun symbol(flow: FlowStability): String = when {
        flow.stable -> FlowStatus.PASSED.symbol
        flow.flaky -> "!"
        else -> FlowStatus.FAILED.symbol
    }
}
