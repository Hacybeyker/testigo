package testigo.network

import java.net.URLDecoder
import java.time.Instant

/**
 * Convierte transacciones de Chucker a HAR 1.2, el formato que importan Chrome DevTools, Charles o Proxyman.
 * Recibe las transacciones ya sanitizadas ([ChuckerTransaction.redacted]).
 */
object Har {

    fun build(transactions: List<ChuckerTransaction>, creatorVersion: String): Map<String, Any?> =
        mapOf(
            "log" to mapOf(
                "version" to "1.2",
                "creator" to mapOf("name" to "testigo", "version" to creatorVersion),
                "entries" to transactions.map { entry(it) },
            ),
        )

    private fun entry(t: ChuckerTransaction): Map<String, Any?> {
        val wait = (t.durationMs ?: 0).coerceAtLeast(0)
        val httpVersion = httpVersion(t.protocol)
        val url = t.url.orEmpty()
        return buildMap {
            put("startedDateTime", Instant.ofEpochMilli(t.requestDate ?: 0).toString())
            put("time", wait)
            put(
                "request",
                buildMap {
                    put("method", t.method ?: "GET")
                    put("url", url)
                    put("httpVersion", httpVersion)
                    put("cookies", emptyList<Any>())
                    put("headers", headers(t.requestHeaders))
                    put("queryString", queryString(url))
                    if (t.requestBody != null || t.requestContentType != null) {
                        put(
                            "postData",
                            buildMap {
                                put("mimeType", t.requestContentType.orEmpty())
                                put("text", if (t.requestBodyEncoded) "" else t.requestBody.orEmpty())
                                if (t.requestBodyEncoded) put("comment", "cuerpo binario: Chucker no lo guarda como texto")
                            },
                        )
                    }
                    put("headersSize", t.requestHeadersSize ?: -1)
                    put("bodySize", t.requestPayloadSize ?: -1)
                },
            )
            put(
                "response",
                mapOf(
                    "status" to (t.responseCode ?: 0),
                    "statusText" to t.responseMessage.orEmpty(),
                    "httpVersion" to httpVersion,
                    "cookies" to emptyList<Any>(),
                    "headers" to headers(t.responseHeaders),
                    "content" to content(t),
                    "redirectURL" to "",
                    "headersSize" to (t.responseHeadersSize ?: -1),
                    "bodySize" to (t.responsePayloadSize ?: -1),
                ),
            )
            put("cache", emptyMap<String, Any>())
            put("timings", mapOf("send" to 0, "wait" to wait, "receive" to 0))
            t.error?.let { put("_error", it) }
            t.graphQlOperationName?.let { put("_graphQlOperation", it) }
        }
    }

    private fun content(t: ChuckerTransaction): Map<String, Any?> = buildMap {
        put("size", t.responsePayloadSize ?: 0)
        put("mimeType", t.responseContentType.orEmpty())
        when {
            t.responseHasImage -> put("comment", "imagen: no se incluye en el HAR")
            t.responseBodyEncoded -> put("comment", "cuerpo binario: Chucker no lo guarda como texto")
            t.responseBody != null -> put("text", t.responseBody)
        }
    }

    private fun headers(headers: List<Header>) = headers.map { mapOf("name" to it.name, "value" to it.value) }

    internal fun queryString(url: String): List<Map<String, String>> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { pair ->
            mapOf("name" to decode(pair.substringBefore('=')), "value" to decode(pair.substringAfter('=', "")))
        }
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)

    internal fun httpVersion(protocol: String?): String = when (protocol?.lowercase()) {
        null, "", "http/1.1" -> "HTTP/1.1"
        "http/1.0" -> "HTTP/1.0"
        "h2", "h2_prior_knowledge" -> "HTTP/2.0"
        "h3", "quic" -> "HTTP/3"
        else -> protocol.uppercase()
    }
}
