package testigo.network

import testigo.redact.Redactor
import testigo.util.Json
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.ResultSet

data class Header(val name: String, val value: String)

/** Una fila de la tabla `transactions` de Chucker 4.x. Los campos que una versión no tenga quedan en null. */
data class ChuckerTransaction(
    val id: Long,
    val requestDate: Long?,
    val responseDate: Long?,
    val tookMs: Long?,
    val protocol: String?,
    val method: String?,
    val url: String?,
    val host: String?,
    val path: String?,
    val requestHeaders: List<Header>,
    val requestHeadersSize: Long?,
    val requestContentType: String?,
    val requestBody: String?,
    val requestBodyEncoded: Boolean,
    val requestPayloadSize: Long?,
    val responseCode: Int?,
    val responseMessage: String?,
    val error: String?,
    val responseHeaders: List<Header>,
    val responseHeadersSize: Long?,
    val responseContentType: String?,
    val responseBody: String?,
    val responseBodyEncoded: Boolean,
    val responsePayloadSize: Long?,
    val responseHasImage: Boolean,
    val graphQlOperationName: String?,
) {
    /** Sin respuesta, con error de red o con status de error. */
    val failed: Boolean get() = error != null || responseCode == null || responseCode >= 400

    /** Duración en ms: la que midió Chucker o, si no, la diferencia entre request y response. */
    val durationMs: Long? get() = tookMs ?: responseDate?.let { end -> requestDate?.let { end - it } }

    /**
     * La transacción sanitizada: URL, headers, bodies y error. Todo lo que se escribe de la red (texto, HAR y
     * resumen) sale de aquí. Los bodies binarios no se escriben, así que se descartan.
     */
    fun redacted(redactor: Redactor): ChuckerTransaction = copy(
        url = url?.let(redactor::text),
        path = path?.let(redactor::text),
        requestHeaders = requestHeaders.map { Header(it.name, redactor.header(it.name, it.value)) },
        requestBody = if (requestBodyEncoded) null else requestBody?.let(redactor::text),
        responseMessage = responseMessage?.let(redactor::text),
        error = error?.let(redactor::text),
        responseHeaders = responseHeaders.map { Header(it.name, redactor.header(it.name, it.value)) },
        responseBody = if (responseBodyEncoded) null else responseBody?.let(redactor::text),
    )
}

/** Columnas de la tabla `transactions` (en minúsculas) y cuántas filas tiene. */
data class ChuckerSchema(val columns: Set<String>, val transactions: Long) {
    /** Las columnas que el runner necesita y esta versión de Chucker no tiene. */
    val missing: List<String> get() = ChuckerDatabase.REQUIRED_COLUMNS.filter { it.lowercase() !in columns }
}

/** Lee la copia local de la base de Chucker (con su `-wal` y `-shm` al lado) con sqlite-jdbc. */
object ChuckerDatabase {
    /** Sin estas columnas no hay filtro por flow, método, URL, host, status ni bodies. Chucker 4.x las tiene todas. */
    val REQUIRED_COLUMNS = listOf("requestDate", "method", "url", "host", "path", "responseCode", "requestBody", "responseBody")

    /** El esquema de la base, para `testigo doctor`. null si no tiene la tabla `transactions`. */
    fun inspect(db: Path): ChuckerSchema? =
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            val columns = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(transactions)").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString("name").lowercase()) }
                }
            }
            if (columns.isEmpty()) {
                null
            } else {
                val count = connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM transactions").use { rows -> if (rows.next()) rows.getLong(1) else 0L }
                }
                ChuckerSchema(columns, count)
            }
        }

    /** Transacciones con `requestDate >= since`, en orden. Con [since] null, todas. */
    fun read(db: Path, since: Long?): List<ChuckerTransaction> =
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            val sql = "SELECT * FROM transactions" + (if (since != null) " WHERE requestDate >= ?" else "") +
                " ORDER BY requestDate, id"
            connection.prepareStatement(sql).use { statement ->
                if (since != null) statement.setLong(1, since)
                statement.executeQuery().use { rows ->
                    val columns = (1..rows.metaData.columnCount).map { rows.metaData.getColumnName(it).lowercase() }.toSet()
                    buildList { while (rows.next()) add(Row(rows, columns).toTransaction()) }
                }
            }
        }

    private class Row(private val rows: ResultSet, private val columns: Set<String>) {
        fun long(name: String): Long? = if (name.lowercase() !in columns) null else rows.getLong(name).takeUnless { rows.wasNull() }
        fun int(name: String): Int? = long(name)?.toInt()
        fun string(name: String): String? = if (name.lowercase() !in columns) null else rows.getString(name)
        fun bool(name: String): Boolean = (long(name) ?: 0L) != 0L
        fun hasBlob(name: String): Boolean = name.lowercase() in columns && rows.getBytes(name) != null

        fun toTransaction() = ChuckerTransaction(
            id = long("id") ?: 0,
            requestDate = long("requestDate"),
            responseDate = long("responseDate"),
            tookMs = long("tookMs"),
            protocol = string("protocol"),
            method = string("method"),
            url = string("url"),
            host = string("host"),
            path = string("path"),
            requestHeaders = headers(string("requestHeaders")),
            requestHeadersSize = long("requestHeadersSize"),
            requestContentType = string("requestContentType"),
            requestBody = string("requestBody"),
            requestBodyEncoded = bool("isRequestBodyEncoded"),
            requestPayloadSize = long("requestPayloadSize"),
            responseCode = int("responseCode"),
            responseMessage = string("responseMessage"),
            error = string("error"),
            responseHeaders = headers(string("responseHeaders")),
            responseHeadersSize = long("responseHeadersSize"),
            responseContentType = string("responseContentType"),
            responseBody = string("responseBody"),
            responseBodyEncoded = bool("isResponseBodyEncoded"),
            responsePayloadSize = long("responsePayloadSize"),
            responseHasImage = hasBlob("responseImageData"),
            graphQlOperationName = string("graphQlOperationName"),
        )
    }

    /** Chucker guarda los headers como JSON: `[{"name": "...", "value": "..."}]`. */
    internal fun headers(json: String?): List<Header> {
        if (json.isNullOrBlank()) return emptyList()
        val parsed = runCatching { Json.parse(json) }.getOrNull() as? List<*> ?: return emptyList()
        return parsed.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"] as? String ?: return@mapNotNull null
            Header(name, map["value"]?.toString().orEmpty())
        }
    }
}
