package testigo.redact

import testigo.util.Json
import java.util.regex.Pattern

/**
 * Valor de una clave declarada dentro de un texto. [start]..[end] es el valor tal como está escrito (con sus
 * comillas) y [replacement] lo que va en su lugar. [masked]: ya estaba enmascarado o no había nada que ocultar.
 */
internal data class KeyHit(val start: Int, val end: Int, val key: String, val replacement: String, val masked: Boolean)

/** Busca los valores de las claves declaradas: en JSON a cualquier profundidad y, si no es JSON, en texto. */
internal object KeyValues {
    private val MASKED = Regex("""\*{3,}\d{0,4}""")
    private const val MIN_CARD_DIGITS = 12

    /** No son secretos: `"password": false` dice que un campo es de contraseña, no la contraseña. */
    val LITERALS = setOf("null", "true", "false")

    fun find(text: String, rules: RedactRules): List<KeyHit> {
        if (rules.keys.isEmpty()) return emptyList()
        return JsonKeys.find(text, rules) ?: TextKeys.find(text, rules)
    }

    fun isMasked(value: String): Boolean = value.isEmpty() || MASKED.matches(value)

    /** `***`; de una clave de tarjeta, `***` y los últimos 4 dígitos. */
    fun mask(normalizedKey: String, value: String, rules: RedactRules): String {
        if (normalizedKey in rules.lastFour) {
            val digits = value.filter { it.isDigit() }
            if (digits.length >= MIN_CARD_DIGITS) return Redactor.MASK + digits.takeLast(4)
        }
        return Redactor.MASK
    }
}

/**
 * JSON completo (un body, `commands.json`, la jerarquía de la pantalla). Recorre el texto sin re-escribirlo:
 * solo cambian los valores enmascarados, el resto queda byte a byte. Un valor que es objeto o lista se reemplaza
 * entero. Los textos que no son de una clave declarada se revisan como texto (una URL con `?token=`, un JSON
 * escapado dentro de un string).
 */
internal object JsonKeys {

    /** null si [text] no es JSON. */
    fun find(text: String, rules: RedactRules): List<KeyHit>? {
        val first = text.firstOrNull { !it.isWhitespace() }
        if (first != '{' && first != '[') return null
        if (runCatching { Json.parse(text) }.isFailure) return null
        return Walker(text, rules).run()
    }

    private class Walker(private val text: String, private val rules: RedactRules) {
        private var pos = 0
        private val hits = mutableListOf<KeyHit>()

        fun run(): List<KeyHit> {
            value(nested = true)
            return hits
        }

        /** [nested]: revisar como texto un string suelto (que no es valor de una clave declarada). */
        private fun value(nested: Boolean) {
            ws()
            when (text[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> {
                    val start = pos
                    pos = stringEnd(pos)
                    if (nested) nestedText(start, pos)
                }
                else -> while (pos < text.length && text[pos] !in ",]} \t\r\n") pos++
            }
        }

        private fun obj() {
            pos++
            ws()
            if (text[pos] == '}') {
                pos++
                return
            }
            while (true) {
                ws()
                val keyEnd = stringEnd(pos)
                val key = decode(pos, keyEnd)
                pos = keyEnd
                ws()
                pos++ // ':'
                ws()
                val start = pos
                val before = hits.size
                val normalized = RedactRules.normalize(key)
                val declared = normalized in rules.keys
                value(nested = !declared)
                if (declared) {
                    // Se reemplaza el valor completo: lo encontrado adentro ya no aplica.
                    hits.subList(before, hits.size).clear()
                    hit(key, normalized, start, pos)?.let { hits += it }
                }
                ws()
                if (text[pos] == ',') {
                    pos++
                } else {
                    pos++ // '}'
                    return
                }
            }
        }

        private fun array() {
            pos++
            ws()
            if (text[pos] == ']') {
                pos++
                return
            }
            while (true) {
                value(nested = true)
                ws()
                if (text[pos] == ',') {
                    pos++
                } else {
                    pos++ // ']'
                    return
                }
            }
        }

        private fun hit(key: String, normalized: String, start: Int, end: Int): KeyHit? {
            val raw = text.substring(start, end)
            val value = when (raw.first()) {
                '"' -> decode(start, end)
                '{', '[' -> if (raw.count { !it.isWhitespace() } == 2) "" else raw
                else -> if (raw in KeyValues.LITERALS) return null else raw
            }
            return KeyHit(start, end, key, Json.quote(KeyValues.mask(normalized, value, rules)), KeyValues.isMasked(value))
        }

        /** Un string que no es valor de una clave declarada puede traer `clave=valor` o un JSON escapado. */
        private fun nestedText(start: Int, end: Int) {
            val content = decode(start, end)
            val inner = KeyValues.find(content, rules).filterNot { it.masked }
            if (inner.isEmpty()) return
            hits += KeyHit(start, end, inner.first().key, Json.quote(Redactor.apply(content, inner)), masked = false)
        }

        private fun decode(start: Int, end: Int): String {
            val raw = text.substring(start, end)
            return if ('\\' in raw) Json.parse(raw) as String else raw.substring(1, raw.length - 1)
        }

        private fun stringEnd(from: Int): Int {
            var i = from + 1
            while (text[i] != '"') i += if (text[i] == '\\') 2 else 1
            return i + 1
        }

        private fun ws() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}

/**
 * Texto libre (logcat, consola, HTML). Reconoce:
 * - `"clave": valor` y `\"clave\": \"valor\"` (JSON dentro de un log, también escapado),
 * - `clave=valor` (query strings, `toString()` de Kotlin) y `clave: valor`,
 * - `Header: valor` hasta el fin de la línea, para los headers declarados (logs de OkHttp).
 */
internal object TextKeys {
    private val WORD: Pattern = Pattern.compile("""(?<![A-Za-z0-9_\-])[A-Za-z][A-Za-z0-9_\-]*""")

    /** Valor sin comillas: termina en un espacio o en un separador. */
    private val BARE: Pattern = Pattern.compile("""[^\s,;&"'<>()\[\]{}]+""")

    /** Número, true o false después de una clave entre comillas. */
    private val SCALAR: Pattern = Pattern.compile("""[^\s,{}\[\]"\\]+""")

    private enum class Quote(val mark: String) { NONE(""), PLAIN("\""), ESCAPED("\\\"") }

    fun find(text: String, rules: RedactRules): List<KeyHit> {
        val hits = mutableListOf<KeyHit>()
        val words = WORD.matcher(text)
        var from = 0
        while (from < text.length && words.find(from)) {
            val start = words.start()
            val end = words.end()
            from = end
            val normalized = RedactRules.normalize(text.substring(start, end))
            if (normalized !in rules.keys) continue
            val hit = valueAfter(text, start, end, normalized, rules) ?: continue
            hits += hit
            from = hit.end
        }
        return hits
    }

    private fun valueAfter(text: String, start: Int, end: Int, normalized: String, rules: RedactRules): KeyHit? {
        val quote = when {
            text.startsWith("\\\"", end) && start >= 2 && text.startsWith("\\\"", start - 2) -> Quote.ESCAPED
            text.getOrNull(end) == '"' && text.getOrNull(start - 1) == '"' -> Quote.PLAIN
            else -> Quote.NONE
        }
        var p = blanks(text, end + quote.mark.length)
        val separator = text.getOrNull(p) ?: return null
        if (separator != ':' && (separator != '=' || quote != Quote.NONE)) return null
        p = blanks(text, p + 1)
        if (p >= text.length) return null

        val key = text.substring(start, end)
        fun hit(valueEnd: Int, value: String, mark: String): KeyHit? {
            if (value.isEmpty() || value in KeyValues.LITERALS) return null
            val replacement = mark + KeyValues.mask(normalized, value, rules) + mark
            return KeyHit(p, valueEnd, key, replacement, KeyValues.isMasked(value))
        }

        return when {
            quote == Quote.NONE && separator == ':' && normalized in rules.headers -> {
                // `Authorization: Bearer …`: el valor es el resto de la línea (o hasta una comilla, dentro de un string).
                var e = p
                while (e < text.length && text[e] != '\n' && text[e] != '\r' && text[e] != '"') e++
                val value = text.substring(p, e).trimEnd()
                hit(p + value.length, value, "")
            }
            quote == Quote.PLAIN && text[p] == '"' -> {
                var e = p + 1
                while (e < text.length && text[e] != '"' && text[e] != '\n') e += if (text[e] == '\\') 2 else 1
                if (e >= text.length || text[e] != '"') return null
                hit(e + 1, text.substring(p + 1, e), "\"")
            }
            quote == Quote.ESCAPED && text.startsWith("\\\"", p) -> {
                val close = text.indexOf("\\\"", p + 2)
                if (close < 0 || text.indexOf('\n', p).let { it in 0 until close }) return null
                hit(close + 2, text.substring(p + 2, close), "\\\"")
            }
            else -> {
                val matcher = (if (quote == Quote.NONE) BARE else SCALAR).matcher(text).region(p, text.length)
                if (!matcher.lookingAt()) return null
                hit(matcher.end(), text.substring(p, matcher.end()), quote.mark)
            }
        }
    }

    private fun blanks(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }
}
