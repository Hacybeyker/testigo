package testigo.redact

/** Un hallazgo del leak scan dentro de un texto. Nunca incluye el valor encontrado. */
data class Finding(val line: Int, val rule: String)

/**
 * Sanitiza la evidencia de texto antes de escribirla en `evidence/` (solo con `--redact` o `redact.enabled`):
 * 1. Los valores de las variables de los flows pasan a `****`.
 * 2. Los valores de las claves y headers declarados (presets y `testigo.yaml`) pasan a `***`.
 * 3. Los patrones activos (jwt, bearer, email, tarjeta…) pasan a `***`; de una tarjeta quedan los últimos 4 dígitos.
 * [leaks] vuelve a revisar un texto ya escrito: es el leak scan.
 */
class Redactor(val rules: RedactRules, env: Map<String, String> = emptyMap()) {
    private val envValues: List<Pair<String, String>> = env.entries
        // `true` o `null` no son secretos, y enmascararlos rompería cualquier JSON.
        .filter { (_, value) -> value.length >= MIN_ENV_LENGTH && value.any { it != '*' } && value !in KeyValues.LITERALS }
        .map { it.key to it.value }
        .sortedByDescending { it.second.length }

    /** false: no enmascara nada, la evidencia queda tal cual. */
    val active: Boolean get() = rules != RedactRules.NONE || envValues.isNotEmpty()

    fun text(value: String): String {
        val withoutEnv = maskEnv(value)
        val keyed = apply(withoutEnv, KeyValues.find(withoutEnv, rules).filterNot { it.masked })
        return TextPatterns.redact(keyed, rules.patterns)
    }

    /** Un header declarado se enmascara entero; los demás pasan por [text]. */
    fun header(name: String, value: String): String =
        if (RedactRules.normalize(name) in rules.headers) MASK else text(value)

    fun maskEnv(value: String): String = envValues.fold(value) { acc, (_, secret) -> acc.replace(secret, ENV_MASK) }

    /**
     * Lo que quedó sin enmascarar en [text]: valores de variables, claves declaradas con su valor a la vista y los
     * patrones activos más [RedactRules.ALWAYS_SCANNED].
     */
    fun leaks(text: String): List<Finding> {
        val lines = LineIndex(text)
        val findings = mutableListOf<Finding>()
        envValues.forEach { (key, secret) ->
            var i = text.indexOf(secret)
            while (i >= 0) {
                findings += Finding(lines.of(i), "variable $key")
                i = text.indexOf(secret, i + secret.length)
            }
        }
        KeyValues.find(text, rules).filterNot { it.masked }.forEach { findings += Finding(lines.of(it.start), "clave ${it.key}") }
        TextPatterns.find(text, rules.patterns + RedactRules.ALWAYS_SCANNED).forEach { (start, pattern) ->
            findings += Finding(lines.of(start), pattern.label)
        }
        return findings.distinct().sortedBy { it.line }
    }

    companion object {
        const val MASK = "***"
        const val ENV_MASK = "****"
        private const val MIN_ENV_LENGTH = 3

        /** Sin sanitizar: todo queda tal como salió de la app, Maestro y Chucker. */
        val NONE = Redactor(RedactRules.NONE)

        internal fun apply(text: String, hits: List<KeyHit>): String {
            if (hits.isEmpty()) return text
            val out = StringBuilder(text.length)
            var last = 0
            hits.sortedBy { it.start }.forEach { hit ->
                if (hit.start < last) return@forEach
                out.append(text, last, hit.start).append(hit.replacement)
                last = hit.end
            }
            return out.append(text, last, text.length).toString()
        }
    }
}

/** Número de línea (desde 1) de una posición del texto. */
private class LineIndex(text: String) {
    private val starts: IntArray = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()

    fun of(position: Int): Int {
        val index = starts.binarySearch(position)
        return if (index >= 0) index + 1 else -index - 1
    }
}

/** Los patrones de [TextPattern] sobre todo el texto. */
internal object TextPatterns {
    private val REGEX: Map<TextPattern, Regex> = mapOf(
        TextPattern.PRIVATE_KEY to Regex(
            """-----BEGIN [A-Z ]*PRIVATE KEY-----(?:[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----|[^\r\n"]*)""",
        ),
        TextPattern.BEARER to Regex("""(?i)\b(bearer)([ \t]+)[A-Za-z0-9._~+/-]{20,}=*"""),
        TextPattern.JWT to Regex("""(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*"""),
        TextPattern.EMAIL to Regex(
            """(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}(?![A-Za-z0-9-])""",
        ),
        // Seguidos (13 a 19 dígitos), en grupos de 4 (4111 1111 1111 1111) o como Amex (3782 822463 10005).
        TextPattern.CARD to Regex(
            """(?<![0-9A-Za-z])(?:[0-9]{13,19}|[0-9]{4}([ -])[0-9]{4}\1[0-9]{4}\1[0-9]{4}(?:\1[0-9]{3})?|3[47][0-9]{2}([ -])[0-9]{6}\2[0-9]{5})(?![0-9A-Za-z])""",
        ),
    )

    fun redact(text: String, patterns: Set<TextPattern>): String =
        TextPattern.entries.filter { it in patterns }.fold(text) { acc, pattern ->
            REGEX.getValue(pattern).replace(acc) { match ->
                if (!valid(pattern, match.value)) match.value else replacement(pattern, match)
            }
        }

    fun find(text: String, patterns: Set<TextPattern>): List<Pair<Int, TextPattern>> =
        TextPattern.entries.filter { it in patterns }.flatMap { pattern ->
            REGEX.getValue(pattern).findAll(text).filter { valid(pattern, it.value) }.map { it.range.first to pattern }.toList()
        }

    private fun replacement(pattern: TextPattern, match: MatchResult): String = when (pattern) {
        TextPattern.BEARER -> match.groupValues[1] + match.groupValues[2] + Redactor.MASK
        TextPattern.CARD -> Redactor.MASK + match.value.filter { it.isDigit() }.takeLast(4)
        else -> Redactor.MASK
    }

    private fun valid(pattern: TextPattern, value: String): Boolean = pattern != TextPattern.CARD || isCard(value.filter { it.isDigit() })

    /**
     * Prefijo y largo de una marca real, y Luhn. Así no pasan por tarjeta los timestamps en ms (empiezan con 1),
     * los de vsync en ns (15 dígitos) ni los IDs que caen en Luhn por azar.
     */
    internal fun isCard(digits: String): Boolean {
        val length = digits.length
        fun prefix(n: Int) = digits.take(n).toInt()
        val brand = when {
            digits[0] == '4' -> length == 13 || length == 16 || length == 19 // Visa
            prefix(2) in 51..55 || prefix(4) in 2221..2720 -> length == 16 // Mastercard
            prefix(2) == 34 || prefix(2) == 37 -> length == 15 // Amex
            prefix(4) == 6011 || prefix(2) == 65 || prefix(3) in 644..649 || prefix(2) == 62 -> length in 16..19 // Discover, UnionPay
            prefix(4) in 3528..3589 -> length in 16..19 // JCB
            prefix(2) == 36 || prefix(3) in 300..305 -> length == 14 // Diners
            else -> false
        }
        return brand && luhn(digits)
    }

    private fun luhn(digits: String): Boolean {
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }
}
