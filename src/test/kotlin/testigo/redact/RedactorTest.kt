package testigo.redact

import testigo.config.RedactConfig
import testigo.config.RedactPreset
import testigo.util.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class RedactorTest {

    @TempDir
    lateinit var tmp: Path

    private val all = Redactor(RedactRules.DEFAULT)

    /** Un JWT de prueba (header, payload y firma inventados). */
    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJxYS11c2VyIn0.c2lnbmF0dXJhLWZhbHNh"

    @Test
    fun `un JWT sembrado en un body queda enmascarado`() {
        val body = """{"data":{"session":"$jwt","name":"QA"}}"""

        val redacted = all.text(body)

        assertEquals("""{"data":{"session":"***","name":"QA"}}""", redacted)
        assertEquals(emptyList<Finding>(), all.leaks(redacted))
    }

    @Test
    fun `sin el preset base el JWT queda y el leak scan lo encuentra`() {
        val withoutBase = Redactor(RedactRules.of(RedactConfig(presets = listOf(RedactPreset.PII, RedactPreset.PAYMENTS))))
        val body = """{"session":"$jwt"}"""

        val redacted = withoutBase.text(body)

        assertEquals(body, redacted)
        assertEquals(listOf(Finding(1, "jwt")), withoutBase.leaks(redacted))
    }

    @Test
    fun `claves JSON a cualquier profundidad, sin distinguir mayusculas ni separadores, sin tocar el resto`() {
        val body = """
            {
              "user": {"Email": "qa@empresa.com", "profile": {"phone_number": 987654321}},
              "Access_Token": "abc123",
              "document": {"type": "DNI", "number": "12345678"},
              "items": [{"password": "x1"}, {"pin": null}],
              "monto": 1234.50
            }
        """.trimIndent()

        val redacted = all.text(body)

        assertEquals(
            """
            {
              "user": {"Email": "***", "profile": {"phone_number": "***"}},
              "Access_Token": "***",
              "document": "***",
              "items": [{"password": "***"}, {"pin": null}],
              "monto": 1234.50
            }
            """.trimIndent(),
            redacted,
        )
        assertTrue(runCatching { Json.parse(redacted) }.isSuccess, "sigue siendo JSON válido")
    }

    @Test
    fun `de una tarjeta quedan los ultimos 4 digitos y el cvv se enmascara entero`() {
        val redacted = all.text("""{"cardNumber":"4111 1111 1111 1111","cvv":"123","nota":"pagó con 5500005555555559"}""")

        assertEquals("""{"cardNumber":"***1111","cvv":"***","nota":"pagó con ***5559"}""", redacted)
    }

    @Test
    fun `un numero que no pasa Luhn o que parece una fecha en ms no es una tarjeta`() {
        val text = "id=4111111111111112 timestamp: 1792180528702"

        assertEquals(text, all.text(text))
    }

    @Test
    fun `en logs enmascara headers, clave=valor, JSON en la linea y JSON escapado`() {
        val logcat = """
            10-16 15:00:00.000  5555  5555 D OkHttp  : Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345
            10-16 15:00:00.001  5555  5555 D OkHttp  : Cookie: session=abc; theme=dark
            10-16 15:00:00.002  5555  5555 D Login   : LoginRequest(user=qa, password=Secreto123, remember=true)
            10-16 15:00:00.003  5555  5555 D OkHttp  : {"user":"qa","password":"Secreto123","otp":123456}
            10-16 15:00:00.004  5555  5555 D Repo    : body={\"token\":\"abc\",\"ok\":true}
            10-16 15:00:00.005  5555  5555 I Perfil  : correo de contacto qa.user+1@empresa.com.pe
            10-16 15:00:00.006  5555  5555 I Http    : GET https://api.qa.example.com/v1/me?access_token=abc&lang=es
        """.trimIndent()

        val redacted = all.text(logcat)

        val lines = redacted.lines()
        assertTrue(lines[0].endsWith("D OkHttp  : Authorization: ***"), lines[0])
        assertTrue(lines[1].endsWith("Cookie: ***"), lines[1])
        assertTrue(lines[2].endsWith("LoginRequest(user=qa, password=***, remember=true)"), lines[2])
        assertTrue(lines[3].endsWith("""{"user":"qa","password":"***","otp":"***"}"""), lines[3])
        assertTrue(lines[4].endsWith("""body={\"token\":\"***\",\"ok\":true}"""), lines[4])
        assertTrue(lines[5].endsWith("correo de contacto ***"), lines[5])
        assertTrue(lines[6].endsWith("/v1/me?access_token=***&lang=es"), lines[6])
        assertEquals(emptyList<Finding>(), all.leaks(redacted))
    }

    @Test
    fun `un string JSON que no es de una clave declarada tambien se revisa`() {
        val redacted = all.text("""{"url":"https://api/x?token=abc","body":"{\"password\":\"x\"}","text":"Email"}""")

        assertEquals("""{"url":"https://api/x?token=***","body":"{\"password\":\"***\"}","text":"Email"}""", redacted)
    }

    @Test
    fun `los valores de las variables pasan a cuatro asteriscos y el leak scan los busca`() {
        val redactor = Redactor(RedactRules.NONE, mapOf("PASSWORD" to "Secreto123", "FLAG" to "true", "CORTA" to "ab"))

        assertEquals("login con **** ok=true ab", redactor.text("login con Secreto123 ok=true ab"))
        assertEquals(listOf(Finding(2, "variable PASSWORD")), redactor.leaks("linea 1\nlinea Secreto123"))
    }

    @Test
    fun `el leak scan encuentra claves con su valor a la vista y dice la linea, nunca el valor`() {
        val text = "ok\n{\"password\": \"abc\"}\nemail=qa@empresa.com\n-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----"

        val leaks = all.leaks(text)

        assertEquals(
            listOf(
                Finding(2, "clave password"),
                Finding(3, "clave email"),
                Finding(3, "email"),
                Finding(4, "clave privada"),
            ),
            leaks,
        )
        assertFalse(leaks.any { "abc" in it.rule || "qa@" in it.rule })
        assertEquals(emptyList<Finding>(), all.leaks(all.text(text)))
    }

    @Test
    fun `los headers declarados se enmascaran enteros y los del proyecto se suman`() {
        val redactor = Redactor(RedactRules.of(RedactConfig(headers = listOf("X-Session-Token"), jsonKeys = listOf("policyNumber"))))

        assertEquals("***", redactor.header("authorization", "Basic cWE6cWE="))
        assertEquals("***", redactor.header("x-session-token", "abc"))
        assertEquals("application/json", redactor.header("Content-Type", "application/json"))
        assertEquals("""{"policy_number":"***"}""", redactor.text("""{"policy_number":"POL-123"}"""))
    }

    @Test
    fun `el leak scan revisa los archivos de texto del flow, salvo raw, y lista los binarios`() {
        val flow = tmp.resolve("flow").createDirectories()
        flow.resolve("logcat.txt").writeText("ok\nAuthorization: Bearer abcdefghijklmnopqrstuvwxyz012345\n")
        flow.resolve("maestro").createDirectories().resolve("commands.json").writeText("""[{"text":"$jwt"}]""")
        flow.resolve("video.mp4").writeBytes(byteArrayOf(0, 1, 2))
        flow.resolve("raw").createDirectories().resolve("logcat.txt").writeText("password=Secreto123")

        val scan = LeakScanner.scan(flow, all)

        assertEquals(2, scan.scanned)
        assertEquals(
            listOf(
                Leak(Path.of("logcat.txt"), 2, "clave Authorization"),
                Leak(Path.of("logcat.txt"), 2, "bearer"),
                Leak(Path.of("maestro/commands.json"), 1, "jwt"),
            ),
            scan.leaks,
        )
        assertEquals(listOf(Path.of("video.mp4")), scan.unscanned)
    }
}
