package testigo.maestro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StepTimelineTest {

    private val steps = StepTimeline.parse(CommandsFixture.JSON)

    @Test
    fun `lee los pasos del flow y deja fuera los que agrega Maestro`() {
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8), steps.map { it.sequence })
        assertEquals(
            listOf("launchApp", "runFlow", "inputText", "tapOn", "back", "extendedWaitUntil", "assertNotVisible"),
            steps.map { it.command },
        )
        assertEquals(listOf(0, 0, 1, 1, 1, 0, 0), steps.map { it.depth })
    }

    @Test
    fun `cada paso trae su hora, su duracion, su estado y su label`() {
        val tap = steps.single { it.command == "tapOn" }

        assertEquals(3400L, tap.startMs)
        assertEquals(11400L, tap.endMs)
        assertEquals("confirmar", tap.label)
        assertEquals("COMPLETED", tap.status)
        assertEquals("FAILED", steps.last().status)
    }

    @Test
    fun `el detalle es el selector, el subflow o la captura, y nunca el texto que se escribe`() {
        assertEquals("\"Ingresar\"", steps.single { it.command == "tapOn" }.detail)
        assertEquals("id=home_root", steps.single { it.command == "extendedWaitUntil" }.detail)
        assertEquals("\"Cargando\"", steps.single { it.command == "assertNotVisible" }.detail)
        assertEquals("login.yaml", steps.single { it.command == "runFlow" }.detail)
        assertNull(steps.single { it.command == "inputText" }.detail, "el texto de inputText puede ser una clave")
        assertTrue(steps.none { "Secreto123" in it.toString() })
    }

    @Test
    fun `un archivo que no es de Maestro 2 da una lista vacia`() {
        assertTrue(StepTimeline.parse("{}").isEmpty())
        assertTrue(StepTimeline.parse("no es json").isEmpty())
        assertTrue(StepTimeline.parse("[]").isEmpty())
    }
}
