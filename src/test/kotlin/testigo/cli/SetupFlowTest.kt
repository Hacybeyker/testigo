package testigo.cli

import testigo.ExitCode
import testigo.setup.CommandLineTools
import testigo.setup.FakeFetcher
import testigo.setup.FakeSetupShell
import testigo.setup.Machine
import testigo.setup.zipWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SetupFlowTest {

    @TempDir
    lateinit var tmp: Path

    private val home get() = tmp.resolve("home")
    private val jdk get() = home.resolve(".testigo/jdk/current")
    private val output = mutableListOf<String>()

    private class FakeAsker(private val many: List<Int>?, private val confirm: Int?) : Asker {
        val asked = mutableListOf<String>()
        var offered: List<Choice> = emptyList()

        override fun one(title: String, choices: List<Choice>, cursor: Int): Int? = confirm.also { asked += title }

        override fun many(title: String, choices: List<Choice>): List<Int>? {
            asked += title
            offered = choices
            return many ?: return null
        }

        override fun text(title: String): String? = null

        override fun answered(label: String, value: String) = Unit
    }

    private val env get() = mapOf(
        "HOME" to home.toString(),
        "SHELL" to "/bin/zsh",
        "PATH" to "/usr/bin:/bin",
        "JAVA_HOME" to jdk.toString(),
        "TESTIGO_JAVA_FOUND" to jdk.toString(),
    )

    private fun flow(asker: Asker?, shell: FakeSetupShell = FakeSetupShell()): SetupFlow {
        val maestro = zipWith(tmp.resolve("zips/maestro.zip"), mapOf("maestro/bin/maestro" to ("#!/bin/sh\n" to "rwxr-xr-x"), "maestro/lib/m.jar" to ("jar" to null)))
        val tools = zipWith(tmp.resolve("zips/cmdline.zip"), mapOf("cmdline-tools/bin/sdkmanager" to ("#!/bin/sh\n" to "rwxr-xr-x")))
        val download = CommandLineTools.download("Mac OS X", "aarch64")!!
        return SetupFlow(
            inspect = {
                Machine.inspect(
                    env = env, osName = "Mac OS X", arch = "aarch64", home = home, javaVersion = "21.0.12.1", javaHomeProperty = jdk,
                    appHome = tmp.resolve("testigo-0.1.0"), maestroVersion = { "2.11.0" },
                )
            },
            asker = asker,
            out = { output += it },
            fetcher = FakeFetcher(mapOf("cli-2.11.0/maestro.zip" to maestro, download.fileName to tools)),
            shell = shell,
            maestroVersion = { "2.11.0" },
        )
    }

    @Test
    fun `revisa sin cambiar nada y termina con 2 si falta algo`() {
        val code = flow(asker = null).run(SetupMode.CHECK)

        assertEquals(ExitCode.ENVIRONMENT, code)
        val text = output.joinToString("\n")
        assertTrue("Pendiente:" in text, text)
        assertTrue("  · Maestro: 2.11.0 · descarga 315 MB de github.com a ~/.maestro" in text, text)
        assertFalse(home.resolve(".maestro").exists() || home.resolve(".zshrc").exists() || home.resolve(".testigo/env.sh").exists())
    }

    @Test
    fun `instala lo elegido tras confirmar y deja la terminal configurada sin root`() {
        home.createDirectories().resolve(".zshrc").writeText("alias ll='ls -l'\n")
        val asker = FakeAsker(many = listOf(0, 1, 2, 3), confirm = 0)
        val shell = FakeSetupShell()

        val code = flow(asker, shell).run(SetupMode.INTERACTIVE)

        assertEquals(ExitCode.OK, code, output.joinToString("\n"))
        assertEquals(listOf("Qué hago en esta máquina", "¿Sigo?"), asker.asked)
        assertTrue(asker.offered.all { it.selected }, "todo viene marcado")
        assertTrue(home.resolve(".maestro/bin/maestro").exists())
        assertTrue(home.resolve("Library/Android/sdk/platform-tools/adb").exists())
        assertTrue(home.resolve("Library/Android/sdk/build-tools/36.0.0/aapt2").exists())
        assertTrue(home.resolve(".android/debug.keystore").exists())

        val envSh = home.resolve(".testigo/env.sh").readText()
        listOf(
            "export JAVA_HOME=\"\$HOME/.testigo/jdk/current\"",
            "export ANDROID_HOME=\"\$HOME/Library/Android/sdk\"",
            "export MAESTRO_CLI_NO_ANALYTICS=\"1\"",
            "testigo_path_add \"\$HOME/.maestro/bin\"    # maestro",
            "testigo_path_add \"\$HOME/Library/Android/sdk/platform-tools\"    # adb",
            "testigo_path_add \"${tmp.resolve("testigo-0.1.0/bin")}\"    # testigo",
        ).forEach { assertTrue(it in envSh, "$it\n---\n$envSh") }
        assertEquals("alias ll='ls -l'\n\n# >>> testigo >>>", home.resolve(".zshrc").readText().lines().take(3).joinToString("\n"))
        assertEquals("alias ll='ls -l'\n", home.resolve(".zshrc.testigo-backup").readText())

        val text = output.joinToString("\n")
        assertTrue("Esto es lo que voy a hacer. Nada usa root ni sudo." in text)
        assertTrue("Abre una terminal nueva (o ejecuta: source ~/.zshrc)" in text, text)
        assertTrue("Siguiente: en la carpeta de tu suite, testigo init" in text)
        assertTrue(shell.interactive.any { (command, _) -> command.first().endsWith("sdkmanager") })
    }

    @Test
    fun `responder que no o cancelar no cambia nada`() {
        assertEquals(ExitCode.OK, flow(FakeAsker(many = listOf(0, 1, 2, 3), confirm = 1)).run(SetupMode.INTERACTIVE))
        assertEquals(ExitCode.CANCELLED, flow(FakeAsker(many = null, confirm = 0)).run(SetupMode.INTERACTIVE))
        assertFalse(home.resolve(".maestro").exists() || home.resolve(".testigo/env.sh").exists() || home.resolve(".zshrc").exists())
    }

    @Test
    fun `si no puede tocar el perfil deja env sh y explica las alternativas sin root`() {
        // Un perfil que no se puede escribir (aquí, una carpeta: falla también como root).
        home.resolve(".zshrc").createDirectories()

        val code = flow(FakeAsker(many = listOf(3), confirm = 0)).run(SetupMode.INTERACTIVE)

        assertEquals(ExitCode.ENVIRONMENT, code)
        assertTrue(home.resolve(".testigo/env.sh").exists())
        val text = output.joinToString("\n")
        assertTrue("✖ No pude modificar ~/.zshrc" in text, text)
        assertTrue("cargar el entorno en cada terminal nueva: . ~/.testigo/env.sh" in text, text)
        assertTrue("testigo encuentra Java, Maestro y el SDK en sus carpetas por defecto" in text, text)
        assertFalse("Abre una terminal nueva" in text, "el perfil no quedó configurado")
    }

    @Test
    fun `la skill de Claude Code se ofrece sin marcar, --yes no la instala y elegida se copia`() {
        tmp.resolve("testigo-0.1.0/skills/testigo-flows").createDirectories().resolve("SKILL.md").writeText("---\nname: testigo-flows\n---\n")
        home.resolve(".claude").createDirectories()
        val skill = home.resolve(".claude/skills/testigo-flows/SKILL.md")

        val cancel = FakeAsker(many = null, confirm = 0)
        flow(cancel).run(SetupMode.INTERACTIVE)
        val index = cancel.offered.indexOfFirst { it.title.startsWith("Claude Code") }
        assertFalse(cancel.offered[index].selected, "es opcional: no viene marcada")
        assertTrue(cancel.offered.filterIndexed { i, _ -> i != index }.all { it.selected })

        flow(asker = null).run(SetupMode.YES)
        assertFalse(skill.exists(), "--yes hace lo pendiente, no lo opcional")

        output.clear()
        val pickSkill = object : Asker {
            override fun one(title: String, choices: List<Choice>, cursor: Int): Int? = 0

            override fun many(title: String, choices: List<Choice>): List<Int>? = listOf(choices.indexOfFirst { it.title.startsWith("Claude Code") })

            override fun text(title: String): String? = null

            override fun answered(label: String, value: String) = Unit
        }
        val code = flow(pickSkill).run(SetupMode.INTERACTIVE)
        assertEquals(ExitCode.OK, code, output.joinToString("\n"))
        assertTrue(skill.exists())
        assertTrue(output.any { "La skill testigo-flows queda lista" in it }, output.joinToString("\n"))
    }

    @Test
    fun `solo hace lo marcado`() {
        val code = flow(FakeAsker(many = listOf(2), confirm = 0)).run(SetupMode.INTERACTIVE)

        assertEquals(ExitCode.ENVIRONMENT, code, "Maestro y el SDK siguen faltando")
        assertTrue(home.resolve(".android/debug.keystore").exists())
        assertFalse(home.resolve(".maestro").exists())
        assertFalse(home.resolve(".testigo/env.sh").exists())
    }
}
