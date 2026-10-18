package testigo.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.Theme

/**
 * Estilos que se leen igual con fondo claro u oscuro. El tema por defecto de Mordant usa colores RGB fijos pensados
 * para fondo oscuro: sobre blanco, el verde de lo marcado (#98c379) o el amarillo de los títulos de la ayuda casi no
 * se ven. Aquí solo hay negrita y los colores ANSI básicos, que cada terminal ajusta a su tema.
 */
object TestigoTheme {
    /** Las claves del tema por defecto con color RGB que usan Clikt (ayuda, errores) y las listas de selección. */
    internal val OVERRIDDEN = listOf(
        "success", "danger", "warning", "info",
        "select.title", "select.cursor", "select.selected",
        "prompt.default", "prompt.choices", "prompt.choices.invalid",
    )

    val theme: Theme = Theme(Theme.Default) {
        styles["success"] = TextColors.green
        styles["danger"] = TextColors.red
        // Clikt usa warning para los títulos de la ayuda e info para los nombres de opciones y comandos.
        styles["warning"] = TextStyles.bold.style
        styles["info"] = TextStyles.bold.style
        styles["select.title"] = TextStyles.bold.style
        styles["select.cursor"] = TextStyles.bold.style
        styles["select.selected"] = TextColors.green
        styles["prompt.default"] = TextStyles.bold.style
        styles["prompt.choices"] = TextStyles.bold.style
        styles["prompt.choices.invalid"] = TextColors.red
    }
}
