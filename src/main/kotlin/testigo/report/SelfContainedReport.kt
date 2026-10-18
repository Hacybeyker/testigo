package testigo.report

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * El `report.html` de Maestro carga Bootstrap (CSS y JS) desde cdn.jsdelivr.net: abrirlo genera tráfico externo.
 * Esto lo deja autocontenido: quita todo recurso remoto, agrega estilos y el colapsable en línea, y fija una
 * Content-Security-Policy que bloquea cualquier carga de red que quedara.
 */
object SelfContainedReport {

    private const val MARKER = "<!-- testigo: reporte autocontenido -->"

    private val REMOTE_LINK = Regex("""<link\b[^>]*\bhref\s*=\s*["'](?:https?:)?//[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val REMOTE_SCRIPT = Regex(
        """<script\b[^>]*\bsrc\s*=\s*["'](?:https?:)?//[^"']*["'][^>]*>\s*</script\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val REMOTE_IMPORT = Regex("""@import\s+(?:url\()?\s*["']?(?:https?:)?//[^;]*;""", RegexOption.IGNORE_CASE)

    /** Solo estilos en línea, scripts en línea e imágenes locales o embebidas. Nada de red. */
    private const val CSP =
        "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data: file:; media-src data: file:"

    /** El subconjunto de Bootstrap que usa el reporte de Maestro. */
    private val STYLE = """
        body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;margin:0;padding:16px;color:#212529;background:#f8f9fa;line-height:1.5}
        h1{font-size:2rem;margin:0 0 16px}h3{font-size:1.75rem;margin:0}h5{font-size:1.25rem;margin:0}h6{font-size:1rem;margin:0}
        .card{background:#fff;border:1px solid rgba(0,0,0,.175);border-radius:6px;overflow:hidden}
        .card-body{padding:16px}.card-header{padding:8px 16px;background:rgba(0,0,0,.03);border-bottom:1px solid rgba(0,0,0,.175)}
        .card-group{display:flex;flex-wrap:wrap;gap:12px}.card-group>.card{flex:1 1 0;min-width:160px}
        .card-title{margin-bottom:8px}.card-text{margin:0}
        .text-center{text-align:center}.text-white{color:#fff}.text-dark{color:#212529!important}.text-danger{color:#dc3545}
        .bg-primary{background:#0d6efd}.bg-success{background:#198754}.bg-danger{background:#dc3545}.bg-warning{background:#ffc107}
        .bg-secondary{background:#6c757d}.bg-light{background:#e9ecef}
        .badge{display:inline-block;padding:.35em .65em;font-size:.75em;font-weight:700;line-height:1;border-radius:6px;color:#fff;white-space:nowrap}
        .btn{display:inline-block;padding:6px 12px;border:1px solid transparent;border-radius:6px;font:inherit;color:#fff;background:#6c757d;cursor:pointer}
        .btn-success{background:#198754}.btn-danger{background:#dc3545}.btn-warning{background:#ffc107;color:#000}.btn-secondary{background:#6c757d}
        .alert{padding:12px 16px;border-radius:6px;border:1px solid transparent;margin-bottom:16px}.alert-danger{background:#f8d7da;color:#58151c;border-color:#f1aeb5}
        .d-flex{display:flex}.justify-content-between{justify-content:space-between}.align-items-center{align-items:center}
        .mb-0{margin-bottom:0}.mb-2{margin-bottom:8px}.mb-3{margin-bottom:16px}.mb-4{margin-bottom:24px}.mt-3{margin-top:16px}.mt-5{margin-top:32px}.me-1{margin-right:4px}
        .collapse:not(.show){display:none}
        img,video{max-width:100%}
        pre{white-space:pre-wrap}
    """.trimIndent()

    /** Reemplaza al JS de Bootstrap: solo abre y cierra los colapsables. */
    private const val SCRIPT =
        "document.addEventListener('click',function(e){var b=e.target.closest('[data-bs-toggle=\"collapse\"]');if(!b)return;" +
            "var t=document.querySelector(b.getAttribute('data-bs-target'));if(!t)return;t.classList.toggle('show');" +
            "b.setAttribute('aria-expanded',t.classList.contains('show'))});"

    fun rewrite(file: Path) {
        file.writeText(rewrite(file.readText()))
    }

    fun rewrite(html: String): String {
        if (MARKER in html) return html
        val cleaned = html
            .replace(REMOTE_LINK, "")
            .replace(REMOTE_SCRIPT, "")
            .replace(REMOTE_IMPORT, "")
        val head = """
            $MARKER
            <meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="$CSP">
            <style>
            $STYLE
            </style>
        """.trimIndent()
        val withHead = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(cleaned)
            ?.let { cleaned.replaceRange(it.range, it.value + "\n" + head) }
            ?: "$head\n$cleaned"
        val script = "<script>$SCRIPT</script>"
        val bodyEnd = withHead.lastIndexOf("</body>", ignoreCase = true)
        return if (bodyEnd >= 0) withHead.replaceRange(bodyEnd, bodyEnd, "$script\n") else "$withHead\n$script"
    }
}
