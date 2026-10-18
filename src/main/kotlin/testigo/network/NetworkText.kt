package testigo.network

import testigo.util.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `network.txt`: cada llamada del flow en texto plano, para leerla sin Chrome ni otra herramienta.
 * Los bodies JSON van re-indentados, sin cambiar ningún valor. Recibe las transacciones ya sanitizadas.
 */
object NetworkText {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val SEPARATOR = "────────────────────────────────────────────────────────────"

    fun render(
        transactions: List<ChuckerTransaction>,
        summary: NetworkSummary,
        zone: ZoneId = ZoneId.systemDefault(),
        /** false: corrida sin `--redact`, los datos van tal cual. */
        sanitized: Boolean = true,
    ): String = buildString {
        appendLine("${transactions.size} llamadas · ${summary.failed} con error · ${summary.slow} lentas (≥ ${summary.slowMs} ms)")
        if (summary.hosts.isNotEmpty()) appendLine("Resumen de: ${summary.hosts.joinToString(", ")}")
        appendLine(
            if (sanitized) {
                "Hora del dispositivo. Datos sensibles como *** y valores de las variables de los flows como ****."
            } else {
                "Hora del dispositivo. Sin sanitizar: headers y bodies tal como los guardó Chucker."
            },
        )
        transactions.forEachIndexed { index, t ->
            appendLine()
            appendLine(SEPARATOR)
            val status = t.responseCode?.toString() ?: "sin respuesta"
            val time = t.requestDate?.let { TIME.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "--:--:--"
            val flags = listOfNotNull(
                "ERROR".takeIf { t.failed },
                "LENTA".takeIf { (t.durationMs ?: 0) >= summary.slowMs },
            ).joinToString(" ")
            appendLine("#${index + 1}  ${t.method ?: "GET"}  $status  ${t.durationMs?.let { "$it ms" } ?: "-"}  $time  $flags".trimEnd())
            appendLine(t.url.orEmpty())
            t.error?.let { appendLine("Error: $it") }
            section("Request headers", headers(t.requestHeaders))
            section("Request body", body(t.requestBody, t.requestContentType, t.requestBodyEncoded, image = false))
            section("Response headers", headers(t.responseHeaders))
            section(
                "Response body" + (t.responseContentType?.let { " ($it)" } ?: ""),
                body(t.responseBody, t.responseContentType, t.responseBodyEncoded, t.responseHasImage),
            )
        }
    }.trimEnd() + "\n"

    private fun StringBuilder.section(title: String, lines: List<String>) {
        appendLine()
        appendLine(title)
        lines.forEach { appendLine("  $it") }
    }

    private fun headers(headers: List<Header>): List<String> =
        if (headers.isEmpty()) listOf("(ninguno registrado)") else headers.map { "${it.name}: ${it.value}" }

    private fun body(text: String?, contentType: String?, encoded: Boolean, image: Boolean): List<String> =
        when {
            image -> listOf("(imagen: no se incluye)")
            encoded -> listOf("(binario: Chucker no lo guarda como texto)")
            text.isNullOrEmpty() -> listOf("(vacío)")
            else -> {
                val looksJson = contentType?.contains("json", ignoreCase = true) == true || text.trimStart().firstOrNull() in setOf('{', '[')
                ((if (looksJson) Json.prettyPrint(text) else null) ?: text).lines()
            }
        }
}
