package testigo.cli

import com.github.ajalt.colormath.model.RGB
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestigoThemeTest {

    @Test
    fun `los estilos usan negrita o los colores de la terminal, no RGB fijos pensados para fondo oscuro`() {
        val keys = TestigoTheme.OVERRIDDEN + listOf("muted", "select.unselected-title", "select.unselected-marker", "prompt.prompt")
        keys.forEach { key ->
            val style = TestigoTheme.theme.style(key)
            assertFalse(style.color is RGB || style.bgColor is RGB, "$key: $style")
        }
        assertTrue(TestigoTheme.theme.style("select.title").bold == true)
    }

    @Test
    fun `las teclas van bajo el titulo, en espanol y sin el blanco de Mordant`() {
        val plain = Terminal(ansiLevel = AnsiLevel.NONE, width = 120)
        val heading = MordantAsker.heading("Qué hago en esta máquina", MordantAsker.keys(multi = true, filterable = false))

        assertEquals(
            "Qué hago en esta máquina\nespacio marca o desmarca · ↑ ↓ mueve · enter confirma · ctrl+c cancela",
            plain.render(heading).trimEnd(),
        )
        assertEquals(
            listOf("↑ ↓" to "mueve", "enter" to "elige", "/" to "busca (esc sale)", "ctrl+c" to "cancela"),
            MordantAsker.keys(multi = false, filterable = true),
        )

        val colored = Terminal(ansiLevel = AnsiLevel.TRUECOLOR, width = 120, theme = TestigoTheme.theme).render(heading)
        assertTrue("\u001b[1mespacio" in colored, colored)
        assertFalse("\u001b[97m" in colored || "38;2;" in colored, "ni blanco brillante ni RGB: $colored")
    }
}
