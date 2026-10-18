package testigo.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ShellEnvTest {

    @TempDir
    lateinit var tmp: Path

    private val home get() = tmp.resolve("home").also { it.toFile().mkdirs() }

    @Test
    fun `elige el archivo de inicio de cada terminal`() {
        val state = home.resolve(".testigo")
        fun detect(shell: String, os: String = "Mac OS X", extra: Map<String, String> = emptyMap()) =
            ShellEnv.detect(mapOf("SHELL" to shell) + extra, home, state, os)

        assertEquals(ShellProfile(ShellKind.ZSH, home.resolve(".zshrc"), state.resolve("env.sh")), detect("/bin/zsh"))
        assertEquals(tmp.resolve("zdot/.zshrc"), detect("/bin/zsh", extra = mapOf("ZDOTDIR" to tmp.resolve("zdot").toString())).profile)
        assertEquals(home.resolve(".bash_profile"), detect("/bin/bash").profile)
        assertEquals(home.resolve(".bashrc"), detect("/bin/bash", os = "Linux").profile)
        assertEquals(ShellProfile(ShellKind.FISH, null, home.resolve(".config/fish/conf.d/testigo.fish")), detect("/usr/local/bin/fish"))
        assertEquals(home.resolve(".profile"), detect("/bin/ksh").profile)

        // En macOS, si el usuario ya usa ~/.profile, crear ~/.bash_profile haría que bash lo deje de leer.
        home.resolve(".profile").writeText("export X=1\n")
        assertEquals(home.resolve(".profile"), detect("/bin/bash").profile)
    }

    @Test
    fun `el entorno usa HOME, no repite carpetas en el PATH y marca lo que maneja`() {
        val entries = listOf(
            EnvEntry.Variable("JAVA_HOME", "${home}/.testigo/jdk/current"),
            EnvEntry.Variable("MAESTRO_CLI_NO_ANALYTICS", "1", "MAESTRO_*"),
            EnvEntry.PathDir(home.resolve(".maestro/bin"), ShellEnv.TAG_MAESTRO, "maestro en el PATH"),
            EnvEntry.PathDir(Path.of("/opt/herramientas/testigo 0.1.0/bin"), ShellEnv.TAG_TESTIGO, "testigo en el PATH"),
        )

        val text = ShellEnv.render(ShellKind.ZSH, entries, home, home.resolve(".zshrc"))

        assertEquals(
            """
            |# Entorno de testigo. Lo genera `testigo setup`: no lo edites a mano, vuelve a ejecutar testigo setup para cambiarlo.
            |# Solo variables de tu usuario, sin root. Lo carga ~/.zshrc.
            |export JAVA_HOME="${'$'}HOME/.testigo/jdk/current"
            |# Maestro sin analytics ni consultas a sus servidores, también cuando lo usas fuera de testigo.
            |export MAESTRO_CLI_NO_ANALYTICS="1"
            |testigo_path_add () { case ":${'$'}PATH:" in *":${'$'}1:"*) ;; *) PATH="${'$'}1:${'$'}PATH" ;; esac ; }
            |testigo_path_add "${'$'}HOME/.maestro/bin"    # maestro
            |testigo_path_add "/opt/herramientas/testigo 0.1.0/bin"    # testigo
            |unset -f testigo_path_add
            |export PATH
            |
            """.trimMargin(),
            text,
        )
        assertEquals(setOf("JAVA_HOME", "MAESTRO_CLI_NO_ANALYTICS", "PATH:maestro", "PATH:testigo"), ShellEnv.managed(text))
        assertEquals("\"\\${'$'}(rm -rf x)\\\"\"", ShellEnv.shQuote("$(rm -rf x)\"", home), "nada se ejecuta al cargarlo")

        val fish = ShellEnv.render(ShellKind.FISH, entries, home, null)
        assertTrue("set -gx JAVA_HOME \"${'$'}HOME/.testigo/jdk/current\"" in fish, fish)
        assertTrue("contains -- \"${'$'}HOME/.maestro/bin\" ${'$'}PATH; or set -gx PATH \"${'$'}HOME/.maestro/bin\" ${'$'}PATH    # maestro" in fish, fish)
        assertEquals(setOf("JAVA_HOME", "MAESTRO_CLI_NO_ANALYTICS", "PATH:maestro", "PATH:testigo"), ShellEnv.managed(fish))
    }

    @Test
    fun `el bloque del perfil se agrega una vez y se actualiza en su lugar`() {
        val block = ShellEnv.block(home.resolve(".testigo/env.sh"), home)
        assertEquals(
            "# >>> testigo >>>\n# Lo agregó `testigo setup`: carga el entorno de testigo (Java, Maestro, Android SDK y el comando testigo). Sin root.\n" +
                "[ -f \"${'$'}HOME/.testigo/env.sh\" ] && . \"${'$'}HOME/.testigo/env.sh\"\n# <<< testigo <<<",
            block,
        )

        val original = "export PATH=\"/opt/homebrew/bin:\$PATH\"\nalias ll='ls -l'"
        val once = ShellEnv.upsert(original, block)
        assertEquals("$original\n\n$block\n", once)
        assertEquals(once, ShellEnv.upsert(once, block), "volver a ejecutar setup no duplica el bloque")

        val other = ShellEnv.block(tmp.resolve("otro/env.sh"), home)
        val moved = ShellEnv.upsert("$once# mío\n", other)
        assertEquals("$original\n\n$other\n# mío\n", moved, "lo de después del bloque se conserva")
        assertEquals("$block\n", ShellEnv.upsert(null, block))
    }
}
