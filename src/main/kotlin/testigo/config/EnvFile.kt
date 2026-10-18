package testigo.config

import testigo.TestigoException

/**
 * Lee archivos de variables con formato KEY=VALOR (por ejemplo, .env.testigo).
 * Los mensajes de error nunca incluyen valores, porque suelen ser credenciales.
 */
object EnvFile {
    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun isValidKey(key: String): Boolean = KEY.matches(key)

    fun parse(text: String, origin: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val content = line.removePrefix("export ").trim()
            val separator = content.indexOf('=')
            val key = if (separator > 0) content.substring(0, separator).trim() else ""
            if (!isValidKey(key)) {
                throw TestigoException("$origin, línea ${index + 1}: se esperaba KEY=VALOR")
            }
            result[key] = unquote(content.substring(separator + 1).trim())
        }
        return result
    }

    private fun unquote(value: String): String {
        val quoted = value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))
        return if (quoted) value.substring(1, value.length - 1) else value
    }
}
