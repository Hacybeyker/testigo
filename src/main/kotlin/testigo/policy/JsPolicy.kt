package testigo.policy

import java.net.URI

/**
 * Revisa el JavaScript de un flow: archivos de `runScript` y expresiones `${…}`.
 * Solo permite `http.<método>('<url fija>')` hacia localhost. Lo que no puede verificar, lo bloquea.
 */
internal object JsPolicy {
    data class Finding(val line: Int, val rule: PolicyRule, val message: String)

    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

    /** Globales que salen a la red o permiten esconder código al análisis. */
    private val BLOCKED = mapOf(
        "fetch" to PolicyRule.EXTERNAL_NETWORK,
        "XMLHttpRequest" to PolicyRule.EXTERNAL_NETWORK,
        "WebSocket" to PolicyRule.EXTERNAL_NETWORK,
        "Java" to PolicyRule.UNVERIFIABLE,
        "Packages" to PolicyRule.UNVERIFIABLE,
        "eval" to PolicyRule.UNVERIFIABLE,
        "Function" to PolicyRule.UNVERIFIABLE,
    )

    /** Objetos a través de los cuales `x.http` sigue siendo el cliente http global. */
    private val GLOBAL_OBJECTS = setOf("globalThis", "this", "self", "window", "global")

    fun check(code: String): List<Finding> {
        val tokens = try {
            JsTokenizer.tokenize(code)
        } catch (e: JsSyntaxException) {
            return listOf(Finding(e.line, PolicyRule.UNVERIFIABLE, "no se puede analizar el script (${e.message})"))
        }
        val findings = mutableListOf<Finding>()
        for ((i, token) in tokens.withIndex()) {
            when (token.kind) {
                JsToken.Kind.IDENT -> {
                    if (isPropertyOfAnotherObject(tokens, i) || isObjectKey(tokens, i)) continue
                    when (token.text) {
                        "http" -> checkHttp(tokens, i)?.let(findings::add)
                        in BLOCKED -> findings += Finding(
                            token.line,
                            BLOCKED.getValue(token.text),
                            "`${token.text}` no está permitido en los scripts de los flows",
                        )
                    }
                }
                JsToken.Kind.STRING -> {
                    val indirect = tokens.getOrNull(i - 1)?.text == "["
                    if (indirect && (token.text == "http" || token.text in BLOCKED)) {
                        findings += Finding(token.line, PolicyRule.UNVERIFIABLE, "acceso indirecto a `${token.text}`: no se puede verificar")
                    }
                }
                else -> Unit
            }
        }
        return findings
    }

    private fun checkHttp(tokens: List<JsToken>, i: Int): Finding? {
        val line = tokens[i].line
        val method = tokens.getOrNull(i + 2)
        if (tokens.getOrNull(i + 1)?.text != "." || method?.kind != JsToken.Kind.IDENT || tokens.getOrNull(i + 3)?.text != "(") {
            return Finding(
                line,
                PolicyRule.UNVERIFIABLE,
                "uso de `http` que no se puede verificar; solo se permite http.<método>('http://127.0.0.1:…')",
            )
        }
        val call = "http.${method.text}"
        val url = tokens.getOrNull(i + 4)
        val next = tokens.getOrNull(i + 5)?.text
        if (url?.kind != JsToken.Kind.STRING || url.interpolated || (next != ")" && next != ",")) {
            return Finding(line, PolicyRule.UNVERIFIABLE, "$call con una URL dinámica; debe ser un texto fijo hacia localhost")
        }
        val host = hostOf(url.text)
            ?: return Finding(line, PolicyRule.EXTERNAL_NETWORK, "$call con una URL sin un host válido")
        return if (host in LOCAL_HOSTS) null else Finding(line, PolicyRule.EXTERNAL_NETWORK, "$call hacia $host; solo se permite localhost")
    }

    private fun hostOf(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        return uri.host?.lowercase()
    }

    /** `algo.http` no es el cliente global, salvo que `algo` sea `globalThis`, `this`… */
    private fun isPropertyOfAnotherObject(tokens: List<JsToken>, i: Int): Boolean {
        if (tokens.getOrNull(i - 1)?.text != ".") return false
        val owner = tokens.getOrNull(i - 2)
        return !(owner?.kind == JsToken.Kind.IDENT && owner.text in GLOBAL_OBJECTS)
    }

    /** `{ http: 1 }`: una clave de objeto, no el cliente. */
    private fun isObjectKey(tokens: List<JsToken>, i: Int): Boolean =
        tokens.getOrNull(i + 1)?.text == ":" && tokens.getOrNull(i - 1)?.text in setOf("{", ",")
}

/** Extrae las expresiones `${…}` de un texto del YAML: Maestro evalúa cada una como JavaScript. */
internal object JsExpressions {
    fun extract(text: String): List<String> {
        val expressions = mutableListOf<String>()
        var start = text.indexOf("\${")
        while (start >= 0) {
            val end = closingBrace(text, start + 2)
            expressions += text.substring(start + 2, end)
            start = text.indexOf("\${", end)
        }
        return expressions
    }

    /** Índice de la `}` que cierra la expresión, o el final del texto si no se cierra. */
    private fun closingBrace(text: String, from: Int): Int {
        var depth = 0
        var quote: Char? = null
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else {
                when (c) {
                    '\'', '"', '`' -> quote = c
                    '{' -> depth++
                    '}' -> if (depth == 0) return i else depth--
                }
            }
            i++
        }
        return text.length
    }
}

internal class JsSyntaxException(message: String, val line: Int) : Exception(message)

/** Token de JavaScript. En los textos, [text] es el valor ya sin comillas ni escapes. */
internal data class JsToken(val kind: Kind, val text: String, val line: Int, val interpolated: Boolean = false) {
    enum class Kind { IDENT, STRING, NUMBER, PUNCT }
}

/**
 * Tokenizador mínimo de JavaScript: separa identificadores, textos (con sus escapes), números y símbolos,
 * e ignora comentarios. El código dentro de `${…}` de una plantilla se tokeniza también.
 * No reconoce expresiones regulares literales: si una confunde al tokenizador, el script queda como no verificable.
 */
internal object JsTokenizer {
    fun tokenize(code: String): List<JsToken> {
        val scanner = Scanner(code)
        scanner.scan(untilBrace = false)
        return scanner.out
    }

    private class Scanner(private val src: String) {
        var out = mutableListOf<JsToken>()
        private var pos = 0
        private var line = 1

        fun scan(untilBrace: Boolean) {
            var depth = 0
            while (pos < src.length) {
                val c = src[pos]
                when {
                    c == '\n' -> { line++; pos++ }
                    c.isWhitespace() -> pos++
                    c == '/' && peek(1) == '/' -> skipLineComment()
                    c == '/' && peek(1) == '*' -> skipBlockComment()
                    c == '\'' || c == '"' -> readString(c)
                    c == '`' -> readTemplate()
                    c.isLetter() || c == '_' || c == '$' -> readIdentifier()
                    c.isDigit() -> readNumber()
                    c == '\\' -> throw JsSyntaxException("escape fuera de un texto", line)
                    else -> {
                        if (untilBrace && c == '}') {
                            if (depth == 0) {
                                pos++
                                return
                            }
                            depth--
                        }
                        if (untilBrace && c == '{') depth++
                        out += JsToken(JsToken.Kind.PUNCT, c.toString(), line)
                        pos++
                    }
                }
            }
            if (untilBrace) throw JsSyntaxException("falta la } de una plantilla", line)
        }

        private fun peek(offset: Int): Char? = src.getOrNull(pos + offset)

        private fun skipLineComment() {
            while (pos < src.length && src[pos] != '\n') pos++
        }

        private fun skipBlockComment() {
            val startLine = line
            val end = src.indexOf("*/", pos + 2)
            if (end < 0) throw JsSyntaxException("comentario sin cerrar", startLine)
            line += src.substring(pos, end).count { it == '\n' }
            pos = end + 2
        }

        private fun readIdentifier() {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '$')) pos++
            if (peek(0) == '\\') throw JsSyntaxException("escape dentro de un identificador", line)
            out += JsToken(JsToken.Kind.IDENT, src.substring(start, pos), line)
        }

        private fun readNumber() {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '.' || src[pos] == '_')) pos++
            out += JsToken(JsToken.Kind.NUMBER, src.substring(start, pos), line)
        }

        private fun readString(quote: Char) {
            val startLine = line
            val value = StringBuilder()
            pos++
            while (true) {
                val c = src.getOrNull(pos) ?: throw JsSyntaxException("texto sin cerrar", startLine)
                when (c) {
                    '\\' -> readEscape(value)
                    quote -> {
                        pos++
                        break
                    }
                    '\n' -> throw JsSyntaxException("texto sin cerrar", startLine)
                    else -> {
                        value.append(c)
                        pos++
                    }
                }
            }
            out += JsToken(JsToken.Kind.STRING, value.toString(), startLine)
        }

        private fun readTemplate() {
            val startLine = line
            val value = StringBuilder()
            val inner = mutableListOf<JsToken>()
            var interpolated = false
            pos++
            while (true) {
                val c = src.getOrNull(pos) ?: throw JsSyntaxException("plantilla sin cerrar", startLine)
                when {
                    c == '\\' -> readEscape(value)
                    c == '`' -> {
                        pos++
                        break
                    }
                    c == '$' && peek(1) == '{' -> {
                        interpolated = true
                        pos += 2
                        val outer = out
                        out = inner
                        scan(untilBrace = true)
                        out = outer
                    }
                    else -> {
                        if (c == '\n') line++
                        value.append(c)
                        pos++
                    }
                }
            }
            out += JsToken(JsToken.Kind.STRING, value.toString(), startLine, interpolated)
            out += inner
        }

        /** Lee el escape que empieza en `\` y agrega su valor a [value]. */
        private fun readEscape(value: StringBuilder) {
            val next = peek(1) ?: throw JsSyntaxException("texto sin cerrar", line)
            pos += 2
            when (next) {
                'n' -> value.append('\n')
                't' -> value.append('\t')
                'r' -> value.append('\r')
                'b' -> value.append('\b')
                'f' -> value.append('\u000C')
                'v' -> value.append('\u000B')
                '0' -> value.append('\u0000')
                'x' -> value.append(hex(2).toChar())
                'u' -> if (peek(0) == '{') {
                    val end = src.indexOf('}', pos)
                    val code = if (end < 0) null else src.substring(pos + 1, end).toIntOrNull(16)
                    if (code == null || code > 0x10FFFF) throw JsSyntaxException("escape \\u{…} inválido", line)
                    value.appendCodePoint(code)
                    pos = end + 1
                } else {
                    value.append(hex(4).toChar())
                }
                '\r' -> {
                    if (peek(0) == '\n') pos++
                    line++
                }
                '\n' -> line++
                else -> value.append(next)
            }
        }

        private fun hex(length: Int): Int {
            val digits = src.substring(pos, minOf(pos + length, src.length))
            val code = digits.takeIf { it.length == length }?.toIntOrNull(16)
                ?: throw JsSyntaxException("escape hexadecimal inválido", line)
            pos += length
            return code
        }
    }
}
