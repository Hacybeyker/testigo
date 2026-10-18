package testigo.network

import testigo.config.NetworkFormat
import testigo.process.CommandResult
import testigo.process.CommandRunner
import testigo.redact.RedactRules
import testigo.redact.Redactor
import testigo.util.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration

class ChuckerTest {

    @TempDir
    lateinit var tmp: Path

    /** Base con el esquema de Chucker 4.x y tres llamadas: una anterior al flow, un GET 200 y un POST 500. */
    private fun chuckerDb(): Path {
        val db = tmp.resolve("device/chucker.db")
        Files.createDirectories(db.parent)
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { it.executeUpdate(SCHEMA) }
            val insert = connection.prepareStatement(
                """INSERT INTO transactions (requestDate, responseDate, tookMs, protocol, method, url, host, path, scheme,
                   requestContentType, requestHeaders, requestBody, isRequestBodyEncoded, responseCode, responseMessage, error,
                   responsePayloadSize, responseContentType, responseHeaders, responseBody, isResponseBodyEncoded, graphQlDetected)
                   VALUES (?, ?, ?, 'h2', ?, ?, ?, ?, 'https', ?, ?, ?, 0, ?, '', NULL, 10, 'application/json', ?, ?, 0, 0)""",
            )
            fun add(date: Long, took: Long, method: String, url: String, path: String, body: String?, code: Int, response: String) {
                insert.setLong(1, date)
                insert.setLong(2, date + took)
                insert.setLong(3, took)
                insert.setString(4, method)
                insert.setString(5, url)
                insert.setString(6, "api.qa.example.com")
                insert.setString(7, path)
                insert.setString(8, if (body != null) "application/json" else null)
                insert.setString(9, """[{"name": "Authorization", "value": "Bearer abc"}]""")
                insert.setString(10, body)
                insert.setInt(11, code)
                insert.setString(12, """[{"name": "content-type", "value": "application/json"}]""")
                insert.setString(13, response)
                insert.executeUpdate()
            }
            add(1_000, 100, "GET", "https://api.qa.example.com/old", "/old", null, 200, "{}")
            add(5_000, 120, "GET", "https://api.qa.example.com/movies?page=1&q=a%20b", "/movies?page=1&q=a%20b", null, 200, """{"ok":true}""")
            add(6_000, 4_000, "POST", "https://api.qa.example.com/login", "/login", """{"user":"qa","password":"Secreto123"}""", 500, "error")
        }
        return db
    }

    @Test
    fun `lee solo las llamadas desde el inicio del flow`() {
        val transactions = ChuckerDatabase.read(chuckerDb(), since = 5_000)

        assertEquals(listOf("/movies?page=1&q=a%20b", "/login"), transactions.map { it.path })
        assertEquals(listOf(Header("Authorization", "Bearer abc")), transactions.first().requestHeaders)
        assertEquals(listOf(false, true), transactions.map { it.failed })
    }

    private val redactor = Redactor(RedactRules.DEFAULT)

    @Test
    fun `el HAR tiene los campos que pide la version 1_2 y sale sanitizado`() {
        val transactions = ChuckerDatabase.read(chuckerDb(), since = 5_000).map { it.redacted(redactor) }

        val har = Json.parse(Json.write(Har.build(transactions, "0.1.0"))) as Map<*, *>
        val log = har["log"] as Map<*, *>
        val entries = log["entries"] as List<*>
        val get = entries[0] as Map<*, *>
        val post = entries[1] as Map<*, *>

        assertEquals("1.2", log["version"])
        assertEquals("1970-01-01T00:00:05Z", get["startedDateTime"])
        assertEquals(120L, get["time"])
        val request = get["request"] as Map<*, *>
        assertEquals("HTTP/2.0", request["httpVersion"])
        assertEquals(listOf(mapOf("name" to "page", "value" to "1"), mapOf("name" to "q", "value" to "a b")), request["queryString"])
        assertEquals("""{"ok":true}""", ((get["response"] as Map<*, *>)["content"] as Map<*, *>)["text"])
        assertEquals(listOf(mapOf("name" to "Authorization", "value" to "***")), request["headers"])
        val postData = (post["request"] as Map<*, *>)["postData"] as Map<*, *>
        assertEquals("""{"user":"qa","password":"***"}""", postData["text"])
        assertEquals(500L, (post["response"] as Map<*, *>)["status"])
    }

    @Test
    fun `el resumen cuenta errores, lentas y agrupa por endpoint sin query`() {
        val transactions = ChuckerDatabase.read(chuckerDb(), since = null)
        val summary = NetworkSummary.of(transactions, hosts = listOf("example.com"), slowMs = 3_000)

        assertEquals(3, summary.requests)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.slow)
        assertEquals("/login", summary.endpoints.first().path, "los endpoints con error van primero")
        assertEquals(listOf("/login", "/old", "/movies"), summary.endpoints.map { it.path })
        assertEquals(0, NetworkSummary.of(transactions, listOf("otro.com"), 3_000).requests)
        val alert = summary.alerts.single()
        assertEquals(NetworkSummary.Alert("POST", "api.qa.example.com", "/login", 500, 4_000, error = true, slow = true), alert)
        assertTrue(NetworkSummary.of(transactions, listOf("otro.com"), 3_000).alerts.isEmpty())
        assertEquals(
            "UnknownHostException",
            NetworkSummary.exceptionName("java.net.UnknownHostException: Unable to resolve host \"api.qa.example.com\": No address"),
        )
    }

    /**
     * adb simulado: `ls databases` lista la base, `date` da la hora y `exec-out … cat` copia desde [deviceDir].
     * Sin [runAsOk], run-as falla como en un build no debuggable; con [root], adbd corre como root.
     */
    private class FakeDevice(
        private val deviceDir: Path,
        private val files: String,
        private val runAsOk: Boolean = true,
        private val root: Boolean = false,
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()
        val copies = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            val args = command.drop(3)
            return when {
                args.take(2) == listOf("shell", "date") -> CommandResult(0, if (args[2] == "+%s%3N") "5000\n" else "5\n", "")
                args == listOf("shell", "id", "-u") -> CommandResult(0, if (root) "0\n" else "2000\n", "")
                "run-as" in args && !runAsOk -> CommandResult(1, "", "run-as: package not debuggable: com.empresa.app")
                "run-as" !in args && !root -> CommandResult(1, "", "ls: Permission denied")
                args.last() == "id" -> CommandResult(0, "uid=10123(u0_a123) gid=10123(u0_a123)\n", "")
                args.last().endsWith("databases") -> CommandResult(0, files, "")
                else -> CommandResult(0, "-rw-rw---- 1 u0_a1 u0_a1 856064 2026-10-16 15:00 databases/chucker.db\n", "")
            }
        }

        fun copy(command: List<String>, target: Path): Boolean {
            copies += command
            val source = deviceDir.resolve(command.last().substringAfterLast("databases/"))
            if (!source.exists()) return false
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            return true
        }
    }

    private fun capture(device: FakeDevice, keepRaw: Boolean = false, format: NetworkFormat = NetworkFormat.TXT) = ChuckerCapture(
        adb = Path.of("/sdk/platform-tools/adb"),
        serial = "emulator-5554",
        packageName = "com.empresa.app",
        database = "auto",
        hosts = emptyList(),
        slowMs = 3_000,
        keepRaw = keepRaw,
        runner = device,
        format = format,
        copy = device::copy,
        sleep = {},
    )

    @Test
    fun `copia la base, filtra por la hora del dispositivo y por defecto escribe el texto y el resumen`() {
        val db = chuckerDb()
        val device = FakeDevice(db.parent, "chucker.db\nchucker.db-wal\nchucker.db-shm\nroom_db.db\n")
        val flowDir = Files.createDirectories(tmp.resolve("evidence/flow"))
        val capture = capture(device)

        capture.start()
        val outcome = capture.finish(flowDir, redactor)

        assertNull(outcome.note)
        assertEquals(2, outcome.summary!!.requests)
        assertEquals(flowDir.resolve("network.txt"), outcome.text)
        assertNull(outcome.har, "por defecto no se escribe el HAR")
        assertFalse(flowDir.resolve("network.har").exists())
        assertTrue(flowDir.resolve("network-summary.json").exists())
        assertFalse(flowDir.resolve("raw").exists(), "sin --keep-raw no queda la base cruda")
        val text = outcome.text!!.readText()
        assertFalse(text.contains("Secreto123") || text.contains("Bearer abc"), text)
        assertEquals(listOf("POST 500 · 4000 ms · api.qa.example.com/login"), outcome.highlights)
    }

    @Test
    fun `con formato ambos escribe texto y HAR`() {
        val db = chuckerDb()
        val flowDir = Files.createDirectories(tmp.resolve("evidence/flow"))
        val capture = capture(FakeDevice(db.parent, "chucker.db\n"), format = NetworkFormat.BOTH)

        capture.start()
        val outcome = capture.finish(flowDir, redactor)

        assertTrue(outcome.har!!.readText().contains("\"version\": \"1.2\""))
        assertTrue(outcome.text!!.exists())
    }

    @Test
    fun `el texto muestra cada llamada con el JSON formateado y sin cambiar valores`() {
        val transactions = ChuckerDatabase.read(chuckerDb(), since = 5_000).map { it.redacted(redactor) }
        val summary = NetworkSummary.of(transactions, emptyList(), 3_000)

        val text = NetworkText.render(transactions, summary, java.time.ZoneOffset.UTC)

        assertTrue(text.startsWith("2 llamadas · 1 con error · 1 lentas (≥ 3000 ms)"), text)
        assertTrue(text.contains("#1  GET  200  120 ms  00:00:05.000\nhttps://api.qa.example.com/movies?page=1&q=a%20b"), text)
        assertTrue(text.contains("#2  POST  500  4000 ms  00:00:06.000  ERROR LENTA"), text)
        assertTrue(text.contains("Request headers\n  Authorization: ***"), text)
        assertTrue(text.contains("Request body\n  {\n    \"user\": \"qa\",\n    \"password\": \"***\"\n  }"), text)
        assertTrue(text.contains("Response body (application/json)\n  {\n    \"ok\": true\n  }"), text)
    }

    @Test
    fun `con keep-raw deja la base cruda en raw`() {
        val db = chuckerDb()
        val flowDir = Files.createDirectories(tmp.resolve("evidence/flow"))
        val capture = capture(FakeDevice(db.parent, "chucker.db\n"), keepRaw = true)

        capture.start()
        capture.finish(flowDir, redactor)

        assertTrue(flowDir.resolve("raw/chucker.db").exists())
    }

    @Test
    fun `explica por que no hay red`() {
        val flowDir = Files.createDirectories(tmp.resolve("evidence/flow"))

        val noChucker = capture(FakeDevice(tmp, "room_db.db\n")).finish(flowDir, redactor)
        val notDebuggable = capture(FakeDevice(tmp, "", runAsOk = false)).finish(flowDir, redactor)

        assertTrue(noChucker.note!!.contains("no tiene base de Chucker"), noChucker.note)
        assertTrue(notDebuggable.note!!.contains("debuggable"), notDebuggable.note)
        assertTrue(notDebuggable.note.contains("adb root"), notDebuggable.note)
        assertNull(noChucker.har)
    }

    @Test
    fun `sin build debuggable lee la base si adbd ya corre como root, sin ejecutar adb root`() {
        val db = chuckerDb()
        val device = FakeDevice(db.parent, "chucker.db\n", runAsOk = false, root = true)
        val flowDir = Files.createDirectories(tmp.resolve("evidence/flow"))
        val capture = capture(device)

        capture.start()
        val outcome = capture.finish(flowDir, redactor)

        assertNull(outcome.note)
        assertEquals(2, outcome.summary!!.requests)
        assertEquals(
            listOf("exec-out", "cat", "/data/data/com.empresa.app/databases/chucker.db"),
            device.copies.single().drop(3),
        )
        assertTrue(device.commands.none { "root" in it }, "nunca ejecuta adb root: reiniciaría adbd")
    }

    companion object {
        private const val SCHEMA = """CREATE TABLE `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `requestDate` INTEGER, `responseDate` INTEGER, `tookMs` INTEGER, `protocol` TEXT, `method` TEXT, `url` TEXT,
            `host` TEXT, `path` TEXT, `scheme` TEXT, `responseTlsVersion` TEXT, `responseCipherSuite` TEXT,
            `requestPayloadSize` INTEGER, `requestContentType` TEXT, `requestHeaders` TEXT, `requestHeadersSize` INTEGER,
            `requestBody` TEXT, `isRequestBodyEncoded` INTEGER NOT NULL, `responseCode` INTEGER, `responseMessage` TEXT,
            `error` TEXT, `responsePayloadSize` INTEGER, `responseContentType` TEXT, `responseHeaders` TEXT,
            `responseHeadersSize` INTEGER, `responseBody` TEXT, `isResponseBodyEncoded` INTEGER NOT NULL,
            `responseImageData` BLOB, `graphQlDetected` INTEGER NOT NULL, `graphQlOperationName` TEXT)"""
    }
}
