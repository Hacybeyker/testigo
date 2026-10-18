package testigo.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.mordant.terminal.Terminal
import testigo.BuildInfo

/** Comando raíz: solo agrupa los subcomandos `setup`, `init`, `doctor`, `list` y `run`. */
class TestigoCommand : CliktCommand(name = "testigo") {
    init {
        versionOption(BuildInfo.VERSION)
        // Los subcomandos heredan esta terminal: colores legibles con fondo claro u oscuro.
        context { terminal = Terminal(theme = TestigoTheme.theme) }
    }

    /** `testigo` sin argumentos muestra la ayuda. */
    override val printHelpOnEmptyArgs = true

    override fun help(context: Context): String =
        "Pruebas E2E locales con Maestro, con evidencia por flow (video, red y logs). Nada de tus apps sale de tu " +
            "máquina. Para empezar: testigo setup (prepara la máquina), testigo init (crea la suite) y testigo doctor."

    override fun run() = Unit
}
