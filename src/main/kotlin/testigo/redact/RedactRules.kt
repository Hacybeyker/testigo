package testigo.redact

import testigo.config.RedactConfig
import testigo.config.RedactPreset

/** Patrones que se buscan en todo el texto, no solo como valor de una clave. */
enum class TextPattern(val label: String) {
    PRIVATE_KEY("clave privada"),
    BEARER("bearer"),
    JWT("jwt"),
    EMAIL("email"),

    /** De 13 a 19 dígitos que empiezan como una tarjeta (2 a 6) y pasan Luhn. Quedan los últimos 4. */
    CARD("card_luhn"),
}

/**
 * Qué se enmascara. Claves y headers van normalizados con [normalize]: `access_token`, `accessToken` y
 * `Access-Token` son la misma clave.
 */
data class RedactRules(
    val headers: Set<String> = emptySet(),
    /** Claves que se enmascaran en JSON (a cualquier profundidad) y en texto. Incluye los headers. */
    val keys: Set<String> = emptySet(),
    /** Claves de tarjeta: de su valor quedan los últimos 4 dígitos. */
    val lastFour: Set<String> = emptySet(),
    val patterns: Set<TextPattern> = emptySet(),
) {
    companion object {
        /** Sin reglas: solo se enmascaran las variables de los flows. */
        val NONE = RedactRules()

        /** El leak scan siempre busca estos, aunque el preset `base` esté desactivado: nunca son evidencia válida. */
        val ALWAYS_SCANNED: Set<TextPattern> = setOf(TextPattern.PRIVATE_KEY, TextPattern.BEARER, TextPattern.JWT)

        fun of(config: RedactConfig): RedactRules {
            val presets = config.presets.distinct().map(RedactPresets::of)
            val headers = (presets.flatMap { it.headers } + config.headers).normalized()
            return RedactRules(
                headers = headers,
                keys = (presets.flatMap { it.keys } + config.jsonKeys).normalized() + headers,
                lastFour = presets.flatMap { it.lastFour }.normalized(),
                patterns = presets.flatMap { it.patterns }.toSet(),
            )
        }

        /** Seguro por defecto: todos los presets activos. */
        val DEFAULT: RedactRules = of(RedactConfig())

        fun normalize(key: String): String = key.lowercase().filter { it.isLetterOrDigit() }

        private fun List<String>.normalized(): Set<String> = map(::normalize).filter { it.isNotEmpty() }.toSet()
    }
}

/** Reglas genéricas incluidas en el runner. Cada proyecto activa las que le apliquen en `redact.presets`. */
object RedactPresets {

    class Preset(
        val headers: List<String>,
        val keys: List<String>,
        val lastFour: List<String> = emptyList(),
        val patterns: List<TextPattern>,
    )

    fun of(preset: RedactPreset): Preset = when (preset) {
        RedactPreset.BASE -> BASE
        RedactPreset.PII -> PII
        RedactPreset.PAYMENTS -> PAYMENTS
    }

    private val BASE = Preset(
        headers = listOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-Api-Key", "X-Auth-Token"),
        keys = listOf(
            "password", "passwd", "pin", "otp", "token", "accessToken", "refreshToken", "idToken", "authToken",
            "sessionToken", "secret", "clientSecret", "apiKey",
        ),
        patterns = listOf(TextPattern.PRIVATE_KEY, TextPattern.BEARER, TextPattern.JWT),
    )

    private val PII = Preset(
        headers = emptyList(),
        keys = listOf(
            "email", "phone", "phoneNumber", "mobilePhone", "cellphone", "document", "documentNumber", "dni", "ssn",
            "birthDate", "dateOfBirth",
        ),
        patterns = listOf(TextPattern.EMAIL),
    )

    private val PAYMENTS = Preset(
        headers = emptyList(),
        keys = listOf("cardNumber", "pan", "cvv", "cvc", "securityCode", "expirationDate", "expiryDate"),
        lastFour = listOf("cardNumber", "pan"),
        patterns = listOf(TextPattern.CARD),
    )
}
