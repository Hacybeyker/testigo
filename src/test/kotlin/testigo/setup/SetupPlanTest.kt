package testigo.setup

import testigo.doctor.Check
import testigo.doctor.CheckStatus
import testigo.policy.MaestroEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SetupPlanTest {

    @TempDir
    lateinit var tmp: Path

    private val home get() = tmp.resolve("home")
    private val app get() = tmp.resolve("herramientas/testigo-0.1.0")
    private val sdk get() = home.resolve("Library/Android/sdk")
    private val jdk get() = home.resolve(".testigo/jdk/current")

    private fun executable(path: Path): Path {
        path.parent.createDirectories()
        path.writeText("#!/bin/sh\n")
        path.toFile().setExecutable(true)
        return path
    }

    /** Un Mac recién formateado: solo el Java que bin/testigo descargó en ~/.testigo/jdk. */
    private fun clean(extra: Map<String, String> = emptyMap()): Map<String, String> = mapOf(
        "HOME" to home.toString(),
        "SHELL" to "/bin/zsh",
        "PATH" to "/usr/bin:/bin",
        "JAVA_HOME" to jdk.toString(),
        "TESTIGO_JAVA_FOUND" to jdk.toString(),
    ) + extra

    private fun inspect(
        env: Map<String, String>,
        wanted: Pair<String, Path>? = null,
        maestroVersion: String? = "2.11.0",
        appHome: Path = app,
    ) = Machine.inspect(
        env = env,
        osName = "Mac OS X",
        arch = "aarch64",
        home = home,
        javaVersion = "21.0.12.1",
        javaHomeProperty = jdk,
        appHome = appHome,
        wanted = wanted,
        maestroVersion = { maestroVersion },
    )

    private fun installEverything() {
        executable(home.resolve(".maestro/bin/maestro"))
        executable(sdk.resolve("platform-tools/adb"))
        executable(sdk.resolve("build-tools/36.0.0/aapt2"))
        home.resolve(".android").createDirectories().resolve("debug.keystore").writeText("key")
    }

    /** Lo que deja la terminal después de cargar ~/.testigo/env.sh. [bin] es la carpeta de testigo que puso en el PATH. */
    private fun configured(bin: Path = app.resolve("bin")): Map<String, String> = clean() - "TESTIGO_JAVA_FOUND" + MaestroEnvironment.REQUIRED + mapOf(
        "ANDROID_HOME" to sdk.toString(),
        "PATH" to listOf(bin, sdk.resolve("platform-tools"), home.resolve(".maestro/bin")).joinToString(":") + ":/usr/bin:/bin",
    )

    /** Lo que deja install.sh: la versión en ~/.testigo/cli/<versión> y cli/current apuntando a ella. */
    private fun installScript(version: String): Path {
        val dir = executable(home.resolve(".testigo/cli/$version/bin/testigo")).parent.parent
        val current = home.resolve(".testigo/cli/current")
        Files.deleteIfExists(current)
        Files.createSymbolicLink(current, Path.of(version))
        return dir
    }

    private fun List<Check>.of(name: String) = first { it.name == name }

    @Test
    fun `en una máquina limpia ofrece todo, sin root y dentro de la carpeta del usuario`() {
        val state = inspect(clean())
        val checks = SetupPlan.checks(state)

        assertEquals(CheckStatus.OK, checks.of("Java").status)
        assertEquals("21.0.12.1 · ~/.testigo/jdk/current · lo encontró testigo; tu terminal no tiene JAVA_HOME", checks.of("Java").detail)
        assertEquals(CheckStatus.FAIL, checks.of("Maestro").status)
        assertEquals("no lo encontré en ~/Library/Android/sdk", checks.of("Android SDK").detail)
        assertEquals(CheckStatus.WARN, checks.of("Debug key").status)
        assertEquals(CheckStatus.WARN, checks.of("Terminal").status)

        val actions = SetupPlan.actions(state)
        assertEquals(listOf("Maestro", "Android SDK", "Debug key", "Terminal"), actions.map { it.name })
        val maestro = actions[0] as SetupAction.InstallMaestro
        assertEquals("2.11.0" to home.resolve(".maestro"), maestro.version to maestro.dir)
        val android = actions[1] as SetupAction.InstallAndroidSdk
        assertEquals(sdk, android.root)
        assertEquals(listOf("platform-tools", "build-tools;36.0.0"), android.packages)
        assertTrue(android.cmdlineTools!!.url.endsWith("commandlinetools-mac_arm64-15859902_latest.zip"))

        val entries = SetupPlan.envEntries(state, maestroInstalled = true, sdkInstalled = true)
        assertEquals(
            listOf("JAVA_HOME", "ANDROID_HOME") + MaestroEnvironment.REQUIRED.keys + listOf("PATH:maestro", "PATH:adb", "PATH:testigo"),
            entries.map { if (it is EnvEntry.Variable) it.name else "PATH:" + (it as EnvEntry.PathDir).tag },
        )
        listOf(home.resolve(".maestro"), sdk, home.resolve(".android/debug.keystore"), home.resolve(".testigo/env.sh"), home.resolve(".zshrc")).forEach {
            assertTrue(it.startsWith(home), "$it queda dentro de la carpeta del usuario")
        }
    }

    @Test
    fun `con todo instalado y la terminal configurada no hay nada que hacer`() {
        installEverything()
        // Lo que escribió setup en una terminal sin nada...
        val first = inspect(clean())
        home.resolve(".testigo").createDirectories().resolve("env.sh").writeText(SetupPlan.rendered(first, SetupPlan.envEntries(first)))
        home.resolve(".zshrc").writeText(ShellEnv.block(home.resolve(".testigo/env.sh"), home) + "\n")

        // ...ya cargado en una terminal nueva.
        val state = inspect(configured())

        assertEquals(emptyList<SetupAction>(), SetupPlan.actions(state))
        assertTrue(SetupPlan.checks(state).all { it.status == CheckStatus.OK }, SetupPlan.checks(state).toString())
    }

    @Test
    fun `configurada pero en una terminal vieja pide abrir otra, sin volver a escribir`() {
        installEverything()
        val state = inspect(clean())
        val entries = SetupPlan.envEntries(state)
        home.resolve(".testigo").createDirectories().resolve("env.sh").writeText(SetupPlan.rendered(state, entries))
        home.resolve(".zshrc").writeText("alias ll='ls -l'\n\n" + ShellEnv.block(home.resolve(".testigo/env.sh"), home) + "\n")

        val again = inspect(clean())
        val terminal = SetupPlan.checks(again).of("Terminal")

        assertEquals(CheckStatus.WARN, terminal.status)
        assertEquals("Abre una terminal nueva o ejecuta: source ~/.zshrc", terminal.fix)
        assertTrue(SetupPlan.actions(again).none { it is SetupAction.ConfigureTerminal })
    }

    @Test
    fun `al regenerar mantiene lo que ya manejaba aunque la terminal ya lo tenga`() {
        installEverything()
        val first = inspect(clean())
        home.resolve(".testigo").createDirectories().resolve("env.sh").writeText(SetupPlan.rendered(first, SetupPlan.envEntries(first)))

        // La terminal cargó env.sh: ahora JAVA_HOME viene de ahí y TESTIGO_JAVA_FOUND ya no está.
        val loaded = inspect(configured())
        val names = SetupPlan.envEntries(loaded).map { if (it is EnvEntry.Variable) it.name else "PATH:" + (it as EnvEntry.PathDir).tag }

        assertTrue("JAVA_HOME" in names && "ANDROID_HOME" in names && "PATH:testigo" in names, names.toString())
    }

    @Test
    fun `Maestro distinto al de testigo yaml se actualiza solo si lo instaló testigo`() {
        installEverything()
        val yaml = tmp.resolve("suite/testigo.yaml")

        val managed = inspect(configured(), wanted = "2.11.0" to yaml, maestroVersion = "2.10.0")
        val update = SetupPlan.actions(managed).filterIsInstance<SetupAction.InstallMaestro>().single()
        assertEquals("2.10.0" to "2.11.0", update.replaces to update.version)
        assertEquals("2.10.0 · ~/.maestro/bin/maestro, pero su testigo.yaml fija 2.11.0", SetupPlan.checks(managed).of("Maestro").detail)

        val brew = executable(tmp.resolve("homebrew/bin/maestro"))
        val external = inspect(configured() + ("PATH" to "${brew.parent}:" + configured()["PATH"]), wanted = "2.11.0" to yaml, maestroVersion = "2.10.0")
        assertTrue(SetupPlan.actions(external).none { it is SetupAction.InstallMaestro })
        assertTrue(SetupPlan.checks(external).of("Maestro").fix.orEmpty().contains("Homebrew"))

        val noSuite = inspect(configured(), wanted = null, maestroVersion = "2.12.0")
        assertEquals(CheckStatus.OK, SetupPlan.checks(noSuite).of("Maestro").status, "sin testigo.yaml no se cambia una versión que funciona")
    }

    @Test
    fun `con el SDK de Android Studio solo agrega lo que falta y usa su sdkmanager`() {
        executable(sdk.resolve("platform-tools/adb"))
        executable(sdk.resolve("cmdline-tools/latest/bin/sdkmanager"))

        val android = SetupPlan.actions(inspect(clean())).filterIsInstance<SetupAction.InstallAndroidSdk>().single()

        assertEquals(listOf("build-tools;36.0.0"), android.packages)
        assertEquals(null, android.cmdlineTools, "ya tiene sdkmanager: no descarga las command-line tools")
    }

    @Test
    fun `con Claude Code ofrece la skill como opcional, y una copia distinta como actualizacion`() {
        installEverything()
        app.resolve("skills/testigo-flows").createDirectories().resolve("SKILL.md").writeText("nueva")
        assertTrue(SetupPlan.actions(inspect(configured())).none { it is SetupAction.InstallSkill }, "sin ~/.claude no se ofrece")

        home.resolve(".claude").createDirectories()
        val missing = inspect(configured())
        assertTrue(SetupPlan.actions(missing).filterIsInstance<SetupAction.InstallSkill>().single().optional)
        assertEquals(CheckStatus.SKIP, SetupPlan.checks(missing).of("Claude Code").status)

        home.resolve(".claude/skills/testigo-flows").createDirectories().resolve("SKILL.md").writeText("vieja")
        val outdated = inspect(configured())
        val update = SetupPlan.actions(outdated).filterIsInstance<SetupAction.InstallSkill>().single()
        assertFalse(update.optional, "actualizar una copia que ya está no es opcional")
        assertEquals(home.resolve(".testigo/backups"), update.backups)
        assertEquals(CheckStatus.WARN, SetupPlan.checks(outdated).of("Claude Code").status)
    }

    @Test
    fun `instalado con install sh, el PATH lleva cli current y una version nueva no pide otro setup`() {
        installEverything()
        val current = home.resolve(".testigo/cli/current")
        val first = inspect(clean(), appHome = installScript("0.3.0"))
        assertEquals(current.resolve("bin"), first.pathBin)

        val env = SetupPlan.rendered(first, SetupPlan.envEntries(first))
        assertTrue("/.testigo/cli/current/bin" in env && "0.3.0" !in env, env)
        home.resolve(".testigo/env.sh").writeText(env)
        home.resolve(".zshrc").writeText(ShellEnv.block(home.resolve(".testigo/env.sh"), home) + "\n")

        // install.sh deja la 0.4.0 y mueve cli/current: una terminal nueva ya la usa, sin otro testigo setup.
        val updated = inspect(configured(bin = current.resolve("bin")), appHome = installScript("0.4.0"))
        assertEquals(emptyList<SetupAction>(), SetupPlan.actions(updated))
        assertTrue(SetupPlan.checks(updated).all { it.status == CheckStatus.OK }, SetupPlan.checks(updated).toString())
    }

    @Test
    fun `una version de install sh que no es la activa, o una instalacion a mano, ponen su propia carpeta`() {
        val old = installScript("0.2.0")
        installScript("0.3.0")
        executable(app.resolve("bin/testigo"))
        val state = home.resolve(".testigo")

        assertEquals(InstallKind.SCRIPT_INACTIVE, Machine.installKind(old, state))
        assertEquals(old.resolve("bin"), inspect(clean(), appHome = old).pathBin)
        assertEquals(InstallKind.MANUAL, Machine.installKind(app, state))
        assertEquals(app.resolve("bin"), inspect(clean()).pathBin)
        assertEquals("0.3.0", Machine.activeVersion(state))
    }

    @Test
    fun `avisa si otro testigo va antes en el PATH, pero no por un enlace a esta instalacion`() {
        installEverything()
        val mine = executable(app.resolve("bin/testigo"))
        val other = executable(home.resolve("herramientas/testigo-0.0.9/bin/testigo"))

        val shadowed = inspect(clean() + ("PATH" to "${other.parent}:/usr/bin:/bin"))
        val check = SetupPlan.checks(shadowed).of("En el PATH")
        assertEquals(CheckStatus.WARN, check.status)
        assertEquals("esta terminal ejecuta primero ~/herramientas/testigo-0.0.9/bin/testigo", check.detail)
        assertTrue(check.fix.orEmpty().startsWith("La configuración de la terminal de testigo setup"), check.fix)
        assertTrue(SetupPlan.actions(shadowed).any { it is SetupAction.ConfigureTerminal })

        // Esta instalación también está en el PATH, pero después y sin que la maneje testigo: setup no la mueve.
        val both = inspect(clean() + ("PATH" to "${other.parent}:${mine.parent}:/usr/bin:/bin"))
        assertEquals("Quita esa carpeta del PATH de tu perfil, o borra esa instalación.", SetupPlan.checks(both).of("En el PATH").fix)

        val link = home.resolve(".local/bin").createDirectories().resolve("testigo")
        Files.createSymbolicLink(link, mine)
        val linked = inspect(clean() + ("PATH" to "${link.parent}:${other.parent}:/usr/bin:/bin"))
        assertTrue(SetupPlan.checks(linked).none { it.name == "En el PATH" }, "un enlace a esta instalación es esta instalación")
    }

    @Test
    fun `en Windows solo revisa`() {
        val state = Machine.inspect(
            env = clean(), osName = "Windows 11", arch = "amd64", home = home, javaVersion = "21", javaHomeProperty = jdk,
            appHome = app, maestroVersion = { null },
        )
        assertEquals(emptyList<SetupAction>(), SetupPlan.actions(state))
    }
}
