package testigo.report

import testigo.BuildInfo
import testigo.android.DeviceInfo
import testigo.artifact.ArtifactInfo
import testigo.maestro.FlowExecutor
import testigo.maestro.FlowResult
import testigo.maestro.FlowStatus
import testigo.maestro.RunResult
import testigo.network.NetworkSummary
import testigo.redact.EvidenceStatus
import testigo.redact.Redactor
import testigo.util.Json
import testigo.util.displayFrom
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import kotlin.io.path.writeText

/** Lo que el resumen necesita además del resultado de los flows. */
data class RunContext(
    val profile: String,
    val device: DeviceInfo,
    val app: ArtifactInfo,
    val maestroVersion: String?,
    val git: GitInfo?,
    /** Opciones resueltas, tal como van a `metadata.json` (sin valores de variables). */
    val options: Map<String, Any?>,
    /** Con `--ci` se escribe además `junit.xml`. */
    val ci: Boolean,
)

data class RunReportFiles(val summary: Path, val metadata: Path, val junit: Path?)

/**
 * Cierre de la corrida, en su carpeta: `summary.md` (para pegar en el PR o el ticket), `metadata.json` y, con
 * `--ci`, `junit.xml`. Pasan por el mismo [Redactor] que la evidencia: con `--redact` también salen sanitizados.
 * `summary.md` no lleva bodies ni query strings.
 */
object RunReport {
    private const val MAX_ALERTS_PER_FLOW = 20
    private const val MAX_ALERTS_IN_CELL = 2

    fun write(result: RunResult, context: RunContext, cwd: Path, redactor: Redactor): RunReportFiles {
        val summary = result.dir.resolve("summary.md").also { it.writeText(redactor.text(summary(result, context, cwd))) }
        val metadata = result.dir.resolve("metadata.json")
            .also { it.writeText(redactor.text(Json.write(metadata(result, context, cwd)))) }
        val junit = if (context.ci) result.dir.resolve("junit.xml").also { it.writeText(redactor.text(junit(result))) } else null
        return RunReportFiles(summary, metadata, junit)
    }

    internal fun summary(result: RunResult, context: RunContext, cwd: Path): String = buildString {
        val git = context.git
        val app = context.app.let { a ->
            listOfNotNull(a.packageName, a.versionName, a.versionCode?.let { "($it)" }).joinToString(" ")
        }
        val title = listOfNotNull("E2E", git?.ticket, git?.commit, app, context.app.artifact.format.label)
        appendLine("## ${title.joinToString(" · ")}")
        val device = context.device.let { d -> "${d.model} · Android ${d.androidRelease}" + (d.sdk?.let { " (API $it)" } ?: "") }
        appendLine((listOf(device, "perfil ${context.profile}") + outcome(result) + FlowExecutor.format(result.duration)).joinToString(" · "))
        appendLine()
        if (result.repeat > 1) {
            appendLine("| Flow | Corridas | Resultado | Duración media | Alertas |")
            appendLine("| --- | --- | --- | --- | --- |")
            result.stability.forEach { flow ->
                val runs = flow.runs.joinToString(" ") { if (it.status == FlowStatus.PASSED) "✅" else "❌" }
                val alerts = alertsCell(flow.runs.flatMap { run -> alertItems(run).map { "#${run.attempt} $it" } })
                appendLine(
                    "| ${cell(flow.name)} | $runs | ${flow.passed}/${flow.runs.size} ${flow.label} | " +
                        "${FlowExecutor.format(flow.meanDuration)} | ${cell(alerts)} |",
                )
            }
        } else {
            appendLine("| Flow | Resultado | Duración | Requests | Alertas |")
            appendLine("| --- | --- | --- | --- | --- |")
            result.flows.forEach { flow ->
                val status = if (flow.status == FlowStatus.PASSED) "✅" else "❌"
                val requests = flow.network?.summary?.requests?.toString() ?: "—"
                appendLine("| ${cell(flow.name)} | $status | ${FlowExecutor.format(flow.duration)} | $requests | ${cell(alertsCell(alertItems(flow)))} |")
            }
        }

        val failed = result.flows.filter { it.status == FlowStatus.FAILED && it.reason != null }
        if (failed.isNotEmpty()) {
            appendLine()
            appendLine("### Por qué falló")
            failed.forEach { flow -> appendLine("- **${cell(result.label(flow))}**: ${cell(flow.reason!!.lines().first())}") }
        }

        result.flows.forEach { flow ->
            val alerts = flow.network?.summary?.alerts.orEmpty()
            if (alerts.isEmpty()) return@forEach
            appendLine()
            appendLine("### Alertas de red · ${cell(result.label(flow))}")
            appendLine("| Método | Endpoint | Status | Veces | Tiempo máx |")
            appendLine("| --- | --- | --- | --- | --- |")
            val groups = groups(alerts)
            groups.take(MAX_ALERTS_PER_FLOW).forEach { group ->
                val time = group.maxMs?.let { "$it ms" + if (group.slow) " (lenta)" else "" } ?: "—"
                appendLine("| ${group.method} | ${cell(group.endpoint)} | ${group.status} | ${group.count} | $time |")
            }
            if (groups.size > MAX_ALERTS_PER_FLOW) {
                appendLine()
                val network = result.dir.relativize(flow.dir.resolve("network.txt")).toString().replace('\\', '/')
                appendLine("… y ${groups.size - MAX_ALERTS_PER_FLOW} más en `$network`.")
            }
        }

        appendLine()
        appendLine(
            when (result.evidenceStatus) {
                EvidenceStatus.OK -> "Evidencia sanitizada (`--redact`), sin hallazgos en el leak scan."
                EvidenceStatus.NO_APTA ->
                    "**Evidencia NO APTA:** el leak scan encontró ${quantity(result.leaks.size, "dato", "datos")} sin enmascarar. No la compartas."
                EvidenceStatus.SIN_SANITIZAR ->
                    "Evidencia sin sanitizar: trae tokens y datos tal cual. Para compartirla fuera del equipo, corre con `--redact`."
            },
        )
        appendLine("Carpeta: `${result.dir.displayFrom(cwd)}`")
    }

    /** `3 flows`, `todos pasaron`; con `--repeat`, `5 flows × 3`, `14 de 15 pasaron` y cuántos son intermitentes. */
    private fun outcome(result: RunResult): List<String> {
        if (result.repeat > 1) {
            val flaky = result.flaky.size
            return listOfNotNull(
                "${quantity(result.stability.size, "flow", "flows")} × ${result.repeat}",
                "${result.passed} de ${result.flows.size} pasaron",
                if (flaky > 0) quantity(flaky, "intermitente", "intermitentes") else null,
            )
        }
        val outcome = when (result.failed) {
            0 -> if (result.flows.size == 1) "pasó" else "todos pasaron"
            1 -> "1 falló"
            else -> "${result.failed} fallaron"
        }
        return listOf(quantity(result.flows.size, "flow", "flows"), outcome)
    }

    internal fun metadata(result: RunResult, context: RunContext, cwd: Path): Map<String, Any?> = linkedMapOf<String, Any?>(
        "run_id" to result.id,
        "started_at" to result.startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        "duration_s" to seconds(result.duration.inWholeMilliseconds),
        "git" to context.git?.let {
            linkedMapOf("repo" to it.repo, "branch" to it.branch, "commit" to it.commit, "dirty" to it.dirty, "ticket" to it.ticket)
        },
        "app" to context.app.let {
            linkedMapOf(
                "package" to it.packageName,
                "version_name" to it.versionName,
                "version_code" to it.versionCode,
                "format" to it.artifact.format.label,
                "sha256" to it.sha256,
                "debuggable" to it.debuggable,
                "artifact" to it.artifact.path.displayFrom(cwd),
            )
        },
        "device" to context.device.let {
            linkedMapOf("serial" to it.serial, "model" to it.model, "android" to it.androidRelease, "sdk" to it.sdk)
        },
        "tools" to linkedMapOf(
            "runner" to BuildInfo.VERSION,
            "maestro" to context.maestroVersion,
            "java" to System.getProperty("java.version"),
        ),
        "options" to context.options,
        "flows" to result.flows.map { flow(it, result, cwd) },
        "passed" to result.passed,
        "failed" to result.failed,
        "exit_code" to result.exitCode.code,
        "evidence_status" to result.evidenceStatus.name,
    ).apply {
        if (result.repeat > 1) {
            put("repeat", result.repeat)
            put("flaky", result.flaky)
        }
    }

    private fun flow(flow: FlowResult, result: RunResult, cwd: Path): Map<String, Any?> {
        val summary = flow.network?.summary
        val runDir = result.dir
        return linkedMapOf<String, Any?>(
            "name" to flow.name,
            "file" to flow.flow.path.displayFrom(cwd),
            "status" to if (flow.status == FlowStatus.PASSED) "passed" else "failed",
            "duration_s" to seconds(flow.duration.inWholeMilliseconds),
            "reason" to flow.reason,
            "crash" to flow.crash,
            "network" to summary?.let {
                linkedMapOf(
                    "requests" to it.requests,
                    "failed" to it.failed,
                    "slow" to it.slow,
                    "alerts" to it.alerts.map { alert ->
                        linkedMapOf(
                            "method" to alert.method,
                            "host" to alert.host,
                            "path" to alert.path,
                            "status" to alert.status,
                            "duration_ms" to alert.durationMs,
                            "error" to alert.error,
                            "slow" to alert.slow,
                            "error_type" to alert.errorType,
                        )
                    },
                )
            },
            "network_note" to flow.network?.note,
            "videos" to flow.videos.map { runDir.relativize(it).toString() },
            "leaks" to flow.leaks.map { linkedMapOf("file" to it.file.toString(), "line" to it.line, "rule" to it.rule) },
        ).apply {
            if (result.repeat > 1) {
                put("attempt", flow.attempt)
                put("dir", runDir.relativize(flow.dir).toString().replace('\\', '/'))
            }
        }
    }

    /** JUnit para CI: un testcase por flow; un flow que falló lleva su motivo. */
    internal fun junit(result: RunResult): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<testsuite name="testigo" tests="${result.flows.size}" failures="${result.failed}" errors="0"""")
        append(""" time="${seconds(result.duration.inWholeMilliseconds)}"""")
        appendLine(""" timestamp="${result.startedAt.toLocalDateTime().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}">""")
        result.flows.forEach { flow ->
            val classname = "testigo." + flow.flow.path.fileName.toString().substringBeforeLast('.')
            val open = """  <testcase name="${xml(result.label(flow))}" classname="${xml(classname)}" time="${seconds(flow.duration.inWholeMilliseconds)}""""
            if (flow.status == FlowStatus.PASSED) {
                appendLine("$open/>")
            } else {
                val reason = flow.reason ?: "falló"
                appendLine("$open>")
                appendLine("""    <failure message="${xml(reason.lines().first())}">${xml(reason)}</failure>""")
                appendLine("  </testcase>")
            }
        }
        appendLine("</testsuite>")
    }

    /** Alertas iguales (método, endpoint y status) juntas, en el orden en que aparecieron. */
    private data class AlertGroup(
        val method: String,
        val endpoint: String,
        val status: String,
        val count: Int,
        val maxMs: Long?,
        val error: Boolean,
        val slow: Boolean,
    )

    private fun groups(alerts: List<NetworkSummary.Alert>): List<AlertGroup> {
        val withHost = alerts.map { it.host }.distinct().size > 1
        return alerts.groupBy { Triple(it.method, endpoint(it, withHost), it.statusLabel) }.map { (key, calls) ->
            AlertGroup(
                method = key.first,
                endpoint = key.second,
                status = key.third,
                count = calls.size,
                maxMs = calls.mapNotNull { it.durationMs }.maxOrNull(),
                error = calls.any { it.error },
                slow = calls.any { it.slow },
            )
        }
    }

    /** `crash: IllegalStateException`, `GET /3/movie/0 → 404 ×3`. */
    private fun alertItems(flow: FlowResult): List<String> = buildList {
        flow.crash?.let { add("crash: " + it.substringBefore(':').substringAfterLast('.')) }
        groups(flow.network?.summary?.alerts.orEmpty()).forEach { group ->
            val outcome = if (group.error) group.status else "${group.maxMs} ms"
            add("${group.method} ${group.endpoint} → $outcome" + if (group.count > 1) " ×${group.count}" else "")
        }
    }

    /** Las primeras alertas y, si hay más, `+3 más`. */
    private fun alertsCell(items: List<String>): String {
        if (items.isEmpty()) return "—"
        val shown = items.take(MAX_ALERTS_IN_CELL).joinToString(", ")
        return if (items.size > MAX_ALERTS_IN_CELL) "$shown, +${items.size - MAX_ALERTS_IN_CELL} más" else shown
    }

    private fun endpoint(alert: NetworkSummary.Alert, withHost: Boolean): String =
        (if (withHost) alert.host else "") + alert.path.ifEmpty { "/" }

    /** Una celda de tabla Markdown: sin saltos de línea y con `|` escapado. */
    private fun cell(text: String): String = text.replace("\r", "").replace('\n', ' ').replace("|", "\\|")

    private fun xml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }

    private fun seconds(millis: Long): Double = Math.round(millis / 100.0) / 10.0

    private fun quantity(n: Int, singular: String, plural: String): String = "$n ${if (n == 1) singular else plural}"
}
