package testigo.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsPolicyTest {

    private fun rules(code: String) = JsPolicy.check(code).map { it.rule }

    @Test
    fun `http hacia localhost con URL fija esta permitido`() {
        val code = """
            http.get('http://127.0.0.1:8765/start')
            var r = http.post("http://localhost:9/x", { body: '{}' })
            http.get(`http://[::1]:9/stop`)
        """.trimIndent()

        assertEquals(emptyList<JsPolicy.Finding>(), JsPolicy.check(code))
    }

    @Test
    fun `http hacia otro host es red externa`() {
        val findings = JsPolicy.check("var r = http.get('https://api.example.com/x')")

        assertEquals(listOf(PolicyRule.EXTERNAL_NETWORK), findings.map { it.rule })
        assertTrue(findings.single().message.contains("api.example.com"), findings.single().message)
    }

    @Test
    fun `un usuario antes del host no engana al lint`() {
        assertEquals(listOf(PolicyRule.EXTERNAL_NETWORK), rules("http.get('http://127.0.0.1@evil.example/x')"))
    }

    @Test
    fun `una URL dinamica no se puede verificar`() {
        assertEquals(listOf(PolicyRule.UNVERIFIABLE), rules("http.get(url)"))
        assertEquals(listOf(PolicyRule.UNVERIFIABLE), rules("http.get('http://127.0.0.1:9' + path)"))
        assertEquals(listOf(PolicyRule.UNVERIFIABLE), rules("http.get(`http://\${host}/x`)"))
    }

    @Test
    fun `usar http de forma indirecta no se puede verificar`() {
        assertEquals(listOf(PolicyRule.UNVERIFIABLE), rules("var h = http\nh.get('https://evil.example')"))
        assertEquals(listOf(PolicyRule.UNVERIFIABLE), rules("globalThis['http'].get('https://evil.example')"))
        assertEquals(listOf(PolicyRule.UNVERIFIABLE), rules("this['\\x68ttp'].get('https://evil.example')"))
        assertEquals(listOf(PolicyRule.EXTERNAL_NETWORK), rules("globalThis.http.get('https://evil.example')"))
    }

    @Test
    fun `fetch, eval y Java estan bloqueados`() {
        assertEquals(listOf(PolicyRule.EXTERNAL_NETWORK), rules("fetch('https://evil.example')"))
        assertEquals(listOf(PolicyRule.UNVERIFIABLE), rules("eval(codigo)"))
        assertEquals(listOf(PolicyRule.UNVERIFIABLE), rules("Java.type('java.net.URL')"))
    }

    @Test
    fun `comentarios, propiedades y claves de objeto no cuentan`() {
        val code = """
            // http.get('https://evil.example')
            /* fetch('https://evil.example') */
            output.http = { http: 1 }
            var texto = 'http.get("https://evil.example")'
        """.trimIndent()

        assertEquals(emptyList<JsPolicy.Finding>(), JsPolicy.check(code))
    }

    @Test
    fun `un script que no se puede analizar queda bloqueado`() {
        assertEquals(listOf(PolicyRule.UNVERIFIABLE), rules("var x = 'sin cerrar"))
        assertEquals(listOf(PolicyRule.UNVERIFIABLE), rules("\\u0068ttp.get('https://evil.example')"))
    }

    @Test
    fun `informa la linea del problema`() {
        val findings = JsPolicy.check("var a = 1\n\nhttp.get('https://evil.example')")

        assertEquals(3, findings.single().line)
    }

    @Test
    fun `extrae las expresiones de un texto del YAML`() {
        assertEquals(listOf("USER", "a ? '}' : b"), JsExpressions.extract("Hola \${USER} y \${a ? '}' : b}"))
        assertEquals(emptyList<String>(), JsExpressions.extract("sin expresiones"))
    }
}
