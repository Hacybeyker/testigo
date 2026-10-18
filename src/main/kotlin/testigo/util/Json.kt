package testigo.util

/**
 * JSON mínimo para la evidencia (HAR, resúmenes) y para leer los headers que guarda Chucker.
 * Valores: `Map<String, Any?>`, `List<Any?>`, `String`, `Number`, `Boolean` y `null`.
 */
object Json {

    fun write(value: Any?, indent: String = "  "): String = buildString { write(value, indent, 0) }.plus("\n")

    fun parse(text: String): Any? = Parser(text).parseDocument()

    /** [value] como string JSON, con comillas y escapes. */
    fun quote(value: String): String = buildString { string(value) }

    /**
     * Re-indenta un JSON sin interpretarlo: números, escapes y orden quedan tal cual vinieron
     * (`1234.50` sigue siendo `1234.50`). null si el texto no es JSON válido.
     */
    fun prettyPrint(text: String, indent: String = "  "): String? {
        if (runCatching { parse(text) }.isFailure) return null
        val out = StringBuilder()
        var level = 0
        var i = 0
        fun newline() {
            out.append('\n').append(indent.repeat(level))
        }
        fun nextSignificant(from: Int): Char? {
            var j = from
            while (j < text.length && text[j].isWhitespace()) j++
            return text.getOrNull(j)
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    val start = i++
                    while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                    out.append(text, start, minOf(i + 1, text.length))
                }
                c == '{' || c == '[' -> {
                    val close = if (c == '{') '}' else ']'
                    if (nextSignificant(i + 1) == close) {
                        out.append(c).append(close)
                        i = text.indexOf(close, i + 1)
                    } else {
                        out.append(c)
                        level++
                        newline()
                    }
                }
                c == '}' || c == ']' -> {
                    level--
                    newline()
                    out.append(c)
                }
                c == ',' -> {
                    out.append(c)
                    newline()
                }
                c == ':' -> out.append(": ")
                c.isWhitespace() -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun StringBuilder.write(value: Any?, indent: String, level: Int) {
        when (value) {
            null -> append("null")
            is String -> string(value)
            is Boolean -> append(value)
            is Double -> append(if (value.isFinite()) value.toString() else "null")
            is Float -> append(if (value.isFinite()) value.toString() else "null")
            is Number -> append(value)
            is Map<*, *> -> container('{', '}', value.entries.toList(), indent, level) { (key, item) ->
                string(key.toString())
                append(": ")
                write(item, indent, level + 1)
            }
            is Iterable<*> -> container('[', ']', value.toList(), indent, level) { write(it, indent, level + 1) }
            else -> string(value.toString())
        }
    }

    private fun <T> StringBuilder.container(
        open: Char,
        close: Char,
        items: List<T>,
        indent: String,
        level: Int,
        item: StringBuilder.(T) -> Unit,
    ) {
        if (items.isEmpty()) {
            append(open).append(close)
            return
        }
        append(open).append('\n')
        items.forEachIndexed { index, value ->
            append(indent.repeat(level + 1))
            item(value)
            if (index < items.lastIndex) append(',')
            append('\n')
        }
        append(indent.repeat(level)).append(close)
    }

    private fun StringBuilder.string(value: String) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseDocument(): Any? {
            val value = value()
            skipWhitespace()
            if (pos != text.length) fail("texto de más")
            return value
        }

        private fun value(): Any? {
            skipWhitespace()
            return when (val c = text.getOrNull(pos) ?: fail("fin inesperado")) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("carácter inesperado '$c'")
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') return result.also { pos++ }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("se esperaba una clave")
                val key = string()
                skipWhitespace()
                expect(':')
                result[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> return result.also { pos++ }
                    else -> fail("se esperaba ',' o '}'")
                }
            }
        }

        private fun array(): List<Any?> {
            pos++
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') return result.also { pos++ }
            while (true) {
                result += value()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> return result.also { pos++ }
                    else -> fail("se esperaba ',' o ']'")
                }
            }
        }

        private fun string(): String {
            pos++
            val result = StringBuilder()
            while (true) {
                val c = text.getOrNull(pos++) ?: fail("texto sin cerrar")
                when (c) {
                    '"' -> return result.toString()
                    '\\' -> when (val escaped = text.getOrNull(pos++) ?: fail("escape incompleto")) {
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'u' -> {
                            val hex = text.substring(pos, minOf(pos + 4, text.length))
                            result.append((hex.toIntOrNull(16) ?: fail("escape \\u inválido")).toChar())
                            pos += 4
                        }
                        else -> result.append(escaped)
                    }
                    else -> result.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: fail("número inválido '$raw'")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("se esperaba $word")
            pos += word.length
            return value
        }

        private fun expect(c: Char) {
            if (peek() != c) fail("se esperaba '$c'")
            pos++
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("JSON inválido en la posición $pos: $message")
    }
}
