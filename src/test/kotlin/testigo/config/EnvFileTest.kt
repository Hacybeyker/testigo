package testigo.config

import testigo.TestigoException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnvFileTest {

    @Test
    fun `lee KEY=VALOR con comentarios, export y comillas`() {
        val env = EnvFile.parse(
            """
            # usuarios de prueba
            USER=qa@example.com

            export PASSWORD="con espacios"
            TOKEN='abc=123'
            """.trimIndent(),
            ".env.testigo",
        )

        assertEquals(mapOf("USER" to "qa@example.com", "PASSWORD" to "con espacios", "TOKEN" to "abc=123"), env)
    }

    @Test
    fun `una linea invalida es un error que no muestra el valor`() {
        val error = assertThrows<TestigoException> { EnvFile.parse("USER=ok\nesto-no-es-valido secreto\n", ".env.testigo") }

        assertTrue(error.message!!.contains("línea 2"))
        assertFalse(error.message!!.contains("secreto"))
    }
}
