package testigo.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonTest {

    @Test
    fun `escribe y vuelve a leer lo mismo`() {
        val value = linkedMapOf(
            "texto" to "comillas \" barra \\ salto\n tab\t y ñ",
            "entero" to 42L,
            "decimal" to 1.5,
            "si" to true,
            "nada" to null,
            "lista" to listOf(1L, "dos", emptyList<Any>(), emptyMap<String, Any>()),
        )

        assertEquals(value, Json.parse(Json.write(value)))
    }

    @Test
    fun `lee los headers que guarda Chucker`() {
        val headers = """[
          {
            "name": "etag",
            "value": "W/\"44a3235ab2\""
          }
        ]"""

        assertEquals(listOf(mapOf("name" to "etag", "value" to "W/\"44a3235ab2\"")), Json.parse(headers))
    }

    @Test
    fun `re-indenta sin tocar numeros ni escapes`() {
        val raw = """{"monto":1234.50,"id":12345678901234567890,"vacio":{},"lista":[],"txt":"a \"b\" , : { c"}"""

        assertEquals(
            """
            {
              "monto": 1234.50,
              "id": 12345678901234567890,
              "vacio": {},
              "lista": [],
              "txt": "a \"b\" , : { c"
            }
            """.trimIndent(),
            Json.prettyPrint(raw),
        )
        assertEquals(null, Json.prettyPrint("no es json"))
    }

    @Test
    fun `un JSON roto es un error`() {
        assertThrows(IllegalArgumentException::class.java) { Json.parse("{\"a\": ") }
        assertThrows(IllegalArgumentException::class.java) { Json.parse("[1, 2] x") }
    }
}
