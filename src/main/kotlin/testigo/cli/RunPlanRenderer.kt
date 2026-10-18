package testigo.cli

import testigo.config.NetworkMode
import testigo.config.Profile
import testigo.config.RedactConfig
import testigo.config.RunPlan
import testigo.config.Source
import testigo.policy.PolicyReport
import testigo.util.displayFrom

/** Texto que imprime `testigo run --dry-run`. Nunca muestra valores de variables, solo sus nombres. */
object RunPlanRenderer {
    private const val LABEL_WIDTH = 15
    private const val MAX_VALUE_WIDTH = 60

    private data class Row(val label: String, val value: String, val source: Source?)

    fun render(plan: RunPlan, dryRun: Boolean, policy: PolicyReport): String = buildString {
        val cwd = plan.cwd
        val profile = plan.profile.value

        appendLine(if (dryRun) "testigo run · simulación (--dry-run): no se ejecuta nada" else "testigo run · plan de ejecución")
        appendLine()

        val rows = listOf(
            Row("Configuración", plan.project.file?.displayFrom(cwd) ?: "sin testigo.yaml (valores por defecto)", null),
            Row("Perfil", "${profile.cliName}: ${profile.purpose}", plan.profile.source),
            Row(
                "App",
                "${plan.artifact.value.path.displayFrom(cwd)} (${plan.artifact.value.format.label})",
                plan.artifact.source,
            ),
            Row("Video", if (plan.record.value) "sí · ${plan.videoBitrate.value} Mbps" else "no", plan.record.source),
            Row("Red", networkLabel(plan), plan.network.source),
            Row("Logs", yesNo(plan.logs.value), plan.logs.source),
            Row("Dispositivo", plan.device.value ?: "automático (el único conectado)", plan.device.source),
            Row("Variables", envLabel(plan), null),
            Row("Evidencia", plan.out.value.displayFrom(cwd), plan.out.source),
            Row("Sanitización", redactLabel(plan), plan.redact.source),
            Row("Datos crudos", rawLabel(plan), plan.keepRaw.source),
            Row("Modo CI", yesNo(plan.ci), null),
            Row("Local-only", policyLabel(policy), null),
        )
        val valueWidth = rows.maxOf { it.value.length }.coerceAtMost(MAX_VALUE_WIDTH)
        rows.forEach { row ->
            val line = buildString {
                append("  ").append(row.label.padEnd(LABEL_WIDTH)).append(row.value.padEnd(valueWidth))
                row.source?.let { append("  [").append(sourceLabel(it, profile)).append(']') }
            }
            appendLine(line.trimEnd())
        }

        appendLine()
        appendLine(flowsTitle(plan))
        if (plan.flows.isEmpty()) appendLine("  (ninguno)")
        val paths = plan.flows.map { it.path.displayFrom(cwd) }
        val pathWidth = paths.maxOfOrNull { it.length } ?: 0
        plan.flows.forEachIndexed { index, flow ->
            val tags = flow.header.tags.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "  [", postfix = "]").orEmpty()
            appendLine("  ${index + 1}. ${paths[index].padEnd(pathWidth)}$tags".trimEnd())
        }

        val violations = policy.lint.violations
        if (violations.isNotEmpty()) {
            appendLine()
            appendLine("Política local-only: ${quantity(violations.size, "violación", "violaciones")}")
            violations.forEach { violation ->
                val location = violation.file.displayFrom(cwd) + (violation.line?.let { ":$it" } ?: "")
                appendLine("  ✗ $location  ${violation.rule.label}: ${violation.message}")
            }
        }

        val problems = policy.lint.problems
        if (problems.isNotEmpty()) {
            appendLine()
            appendLine("Formato de Maestro: ${quantity(problems.size, "archivo que no acepta", "archivos que no acepta")}")
            problems.forEach { problem ->
                val location = problem.file.displayFrom(cwd) + (problem.line?.let { ":$it" } ?: "")
                appendLine("  ✗ $location  ${problem.message}")
                appendLine("    ${problem.fix}")
                problem.snippet.forEach { appendLine("      $it") }
            }
        }

        val warnings = plan.warnings + policy.removedEnv.map { "$it está en tu entorno; el runner la quita al lanzar Maestro." }
        if (warnings.isNotEmpty()) {
            appendLine()
            appendLine("Avisos")
            warnings.forEach { appendLine("  ! $it") }
        }

        appendLine()
        appendLine("Comando equivalente")
        appendLine("  ${EquivalentCommand.build(plan)}")
        if (plan.env.inlineKeys.isNotEmpty()) {
            appendLine("  (no incluye las variables de --env: ${plan.env.inlineKeys.joinToString(", ")})")
        }
    }.trimEnd()

    private fun flowsTitle(plan: RunPlan): String = buildString {
        append("Flows: ${plan.flows.size} de ${plan.discoveredFlows.size}")
        val filters = buildList {
            if (plan.tags.isNotEmpty()) add("tags: ${plan.tags.joinToString(", ")}")
            if (plan.excludeTags.isNotEmpty()) add("excluye: ${plan.excludeTags.joinToString(", ")}")
        }
        if (filters.isNotEmpty()) append(" (${filters.joinToString(" · ")})")
    }

    private fun envLabel(plan: RunPlan): String {
        val env = plan.env
        val parts = buildList {
            if (env.fileKeys.isNotEmpty()) {
                val name = env.file?.displayFrom(plan.cwd) ?: "archivo"
                add("${env.fileKeys.joinToString(", ")} ($name)")
            }
            if (env.inlineKeys.isNotEmpty()) add("${env.inlineKeys.joinToString(", ")} (--env)")
        }
        return if (parts.isEmpty()) "ninguna" else parts.joinToString(" · ")
    }

    private fun sourceLabel(source: Source, profile: Profile): String =
        if (source == Source.PROFILE) "perfil ${profile.cliName}" else source.label

    private fun networkLabel(plan: RunPlan): String = when (plan.network.value) {
        NetworkMode.CHUCKER -> "chucker · ${plan.networkFormat.value.label}"
        NetworkMode.NONE -> "no"
    }

    private fun rawLabel(plan: RunPlan): String = when {
        plan.keepRaw.value -> "se conservan en raw/"
        plan.redact.value -> "se eliminan tras sanitizar"
        else -> "se eliminan"
    }

    private fun redactLabel(plan: RunPlan): String {
        if (!plan.redact.value) return "no · datos tal cual, sin leak scan"
        return redactLabel(plan.project.config.redact)
    }

    private fun redactLabel(redact: RedactConfig): String = buildList {
        add(redact.presets.distinct().joinToString(", ") { it.cliName }.ifEmpty { "sin presets" })
        if (redact.headers.isNotEmpty()) add("+${quantity(redact.headers.size, "header", "headers")}")
        if (redact.jsonKeys.isNotEmpty()) add("+${quantity(redact.jsonKeys.size, "clave", "claves")}")
        add("leak scan")
    }.joinToString(" · ")

    private fun policyLabel(policy: PolicyReport): String {
        val lint = policy.lint
        val files = quantity(lint.checkedFiles.size, "archivo revisado", "archivos revisados")
        return if (lint.passed) "sin violaciones · $files" else "${quantity(lint.violations.size, "violación", "violaciones")} · $files"
    }

    private fun quantity(n: Int, singular: String, plural: String): String = "$n ${if (n == 1) singular else plural}"

    private fun yesNo(value: Boolean): String = if (value) "sí" else "no"
}
