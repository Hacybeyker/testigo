package testigo.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SelfContainedReportTest {

    /** Recorte del report.html que genera Maestro 2.11.0 con --format HTML-DETAILED. */
    private val maestroReport = """
        <html>
          <head>
            <title>Maestro Test Report</title>
            <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
          </head>
          <body>
            <div class="card mb-4">
              <button class="btn btn-success" type="button" data-bs-toggle="collapse" data-bs-target="#flow-0-abrir_app">abrir_app : SUCCESS</button>
              <div class="collapse" id="flow-0-abrir_app">✅ 1. Launch app</div>
              <style>.step-item { border-left: 3px solid #dee2e6; }</style>
              <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.min.js"></script>
            </div>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun `quita los recursos remotos y bloquea la red con CSP`() {
        val html = SelfContainedReport.rewrite(maestroReport)

        assertFalse(html.contains("cdn.jsdelivr.net"), html)
        assertFalse(Regex("""(src|href)\s*=\s*["']https?://""").containsMatchIn(html), html)
        assertTrue(html.contains("Content-Security-Policy"), html)
        assertTrue(html.contains("default-src 'none'"), html)
        assertTrue(html.contains("<meta charset=\"utf-8\">"), html)
    }

    @Test
    fun `conserva el contenido, los estilos propios y el colapsable`() {
        val html = SelfContainedReport.rewrite(maestroReport)

        assertTrue(html.contains("abrir_app : SUCCESS"), html)
        assertTrue(html.contains(".step-item { border-left"), html)
        assertTrue(html.contains(".collapse:not(.show){display:none}"), html)
        assertTrue(html.contains("classList.toggle('show')"), html)
        assertTrue(html.indexOf("<script>") < html.indexOf("</body>"), "el script va antes de </body>")
    }

    @Test
    fun `aplicarlo dos veces no cambia nada`() {
        val once = SelfContainedReport.rewrite(maestroReport)

        assertEquals(once, SelfContainedReport.rewrite(once))
    }
}
