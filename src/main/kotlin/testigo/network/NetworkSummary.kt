package testigo.network

/**
 * Resumen de la red de un flow: una fila por endpoint (método, host y path sin query) y las alertas, en el orden
 * en que ocurrieron. Solo entran los [hosts] del backend; vacío, todos.
 */
data class NetworkSummary(
    val requests: Int,
    val failed: Int,
    val slow: Int,
    val slowMs: Long,
    val hosts: List<String>,
    val endpoints: List<Endpoint>,
    val alerts: List<Alert> = emptyList(),
) {
    /** Una llamada con error (status ≥ 400, sin respuesta o error de red) o lenta. Path sin query. */
    data class Alert(
        val method: String,
        val host: String,
        val path: String,
        /** null: sin respuesta. */
        val status: Int?,
        val durationMs: Long?,
        val error: Boolean,
        val slow: Boolean,
        /** Sin respuesta: la excepción que guardó Chucker, como `UnknownHostException` o `SocketTimeoutException`. */
        val errorType: String? = null,
    ) {
        /** `500`, `sin respuesta (UnknownHostException)` */
        val statusLabel: String get() = status?.toString() ?: ("sin respuesta" + (errorType?.let { " ($it)" } ?: ""))
    }

    /**
     * Las alertas para la consola, agrupadas por método, status y endpoint en el orden en que aparecieron:
     * `GET sin respuesta (UnknownHostException) · api.themoviedb.org/3/discover/movie ×5`.
     */
    fun alertLines(): List<String> =
        alerts.groupBy { Triple(it.method, it.statusLabel, it.host + it.path) }.map { (key, calls) ->
            val maxMs = calls.mapNotNull { it.durationMs }.maxOrNull()
            listOfNotNull("${key.first} ${key.second}", maxMs?.let { "$it ms" }, key.third).joinToString(" · ") +
                if (calls.size > 1) " ×${calls.size}" else ""
        }

    data class Endpoint(
        val method: String,
        val host: String,
        val path: String,
        val count: Int,
        val statuses: Map<String, Int>,
        val failed: Int,
        val slow: Int,
        val maxMs: Long?,
        val avgMs: Long?,
    )

    fun toJson(): Map<String, Any?> = mapOf(
        "requests" to requests,
        "failed" to failed,
        "slow" to slow,
        "slow_ms" to slowMs,
        "hosts" to hosts,
        "endpoints" to endpoints.map {
            mapOf(
                "method" to it.method,
                "host" to it.host,
                "path" to it.path,
                "count" to it.count,
                "statuses" to it.statuses,
                "failed" to it.failed,
                "slow" to it.slow,
                "max_ms" to it.maxMs,
                "avg_ms" to it.avgMs,
            )
        },
        "alerts" to alerts.map {
            mapOf(
                "method" to it.method,
                "host" to it.host,
                "path" to it.path,
                "status" to it.status,
                "duration_ms" to it.durationMs,
                "error" to it.error,
                "slow" to it.slow,
                "error_type" to it.errorType,
            )
        },
    )

    companion object {
        /** `java.net.UnknownHostException: Unable to resolve host…` → `UnknownHostException`. Nunca el mensaje. */
        internal fun exceptionName(error: String): String? =
            error.substringBefore(':').trim().substringAfterLast('.').takeIf { it.isNotEmpty() && it.length <= 80 && ' ' !in it }

        /**
         * Con [hosts] vacío entran todas las llamadas; si no, solo las de esos dominios y sus subdominios.
         * Falla: sin respuesta, error de red o status ≥ 400. Lenta: dura [slowMs] o más.
         */
        fun of(transactions: List<ChuckerTransaction>, hosts: List<String>, slowMs: Long): NetworkSummary {
            val selected = transactions.filter { t ->
                hosts.isEmpty() || hosts.any { h -> t.host.equals(h, ignoreCase = true) || t.host?.endsWith(".$h", ignoreCase = true) == true }
            }
            val endpoints = selected
                .groupBy { Triple(it.method ?: "GET", it.host.orEmpty(), (it.path ?: "").substringBefore('?')) }
                .map { (key, calls) ->
                    val durations = calls.mapNotNull { it.durationMs }
                    NetworkSummary.Endpoint(
                        method = key.first,
                        host = key.second,
                        path = key.third,
                        count = calls.size,
                        statuses = calls.groupingBy { it.responseCode?.toString() ?: "sin respuesta" }.eachCount().toSortedMap(),
                        failed = calls.count { it.failed },
                        slow = calls.count { (it.durationMs ?: 0) >= slowMs },
                        maxMs = durations.maxOrNull(),
                        avgMs = durations.takeIf { it.isNotEmpty() }?.average()?.toLong(),
                    )
                }
                .sortedWith(compareByDescending<Endpoint> { it.failed }.thenByDescending { it.count })
            return NetworkSummary(
                requests = selected.size,
                failed = selected.count { it.failed },
                slow = selected.count { (it.durationMs ?: 0) >= slowMs },
                slowMs = slowMs,
                hosts = hosts,
                endpoints = endpoints,
                alerts = selected
                    .filter { it.failed || (it.durationMs ?: 0) >= slowMs }
                    .map {
                        Alert(
                            method = it.method ?: "GET",
                            host = it.host.orEmpty(),
                            path = (it.path ?: "").substringBefore('?'),
                            status = it.responseCode,
                            durationMs = it.durationMs,
                            error = it.failed,
                            slow = (it.durationMs ?: 0) >= slowMs,
                            errorType = it.error?.let(::exceptionName),
                        )
                    },
            )
        }
    }
}
