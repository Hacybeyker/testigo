package testigo.cli

import testigo.doctor.CheckStatus
import testigo.doctor.DoctorReport
import testigo.doctor.DoctorSection

/** Texto de `testigo doctor`: una sección a la vez, para mostrarla apenas termina. */
object DoctorRenderer {
    private const val LABEL_WIDTH = 12

    const val HEADER = "testigo doctor · revisa la máquina, el dispositivo y la app sin instalar ni cambiar nada"

    fun section(section: DoctorSection): String = buildString {
        appendLine()
        appendLine(section.title)
        section.checks.forEach { check ->
            appendLine("  ${symbol(check.status)} ${check.name.padEnd(LABEL_WIDTH)}${check.detail}".trimEnd())
            check.fix?.let { appendLine(" ".repeat(4 + LABEL_WIDTH) + "→ $it") }
        }
    }

    fun summary(report: DoctorReport): String {
        val failures = report.failures
        val warnings = report.warnings
        val avisos = "$warnings ${if (warnings == 1) "aviso" else "avisos"}"
        return when {
            failures == 0 && warnings == 0 -> "Todo listo para testigo run."
            failures == 0 -> "Listo para testigo run, con $avisos."
            else -> {
                val problemas = "$failures ${if (failures == 1) "problema" else "problemas"}"
                val withWarnings = if (warnings > 0) " y $avisos" else ""
                "$problemas$withWarnings: corrige ${if (failures == 1) "el problema" else "los problemas"} antes de testigo run."
            }
        }
    }

    private fun symbol(status: CheckStatus): String = when (status) {
        CheckStatus.OK -> "✔"
        CheckStatus.WARN -> "!"
        CheckStatus.FAIL -> "✖"
        CheckStatus.SKIP -> "–"
    }
}
