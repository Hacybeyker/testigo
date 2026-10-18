package testigo.cli

import testigo.config.AppConfig
import testigo.config.LoadedProject
import testigo.config.NetworkMode
import testigo.config.Profile
import testigo.config.ProjectConfig
import testigo.config.RunOptions
import testigo.config.RunPlanner
import testigo.maestro.FlowHeader
import testigo.maestro.FlowHeaderReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EquivalentCommandTest {

    @TempDir
    lateinit var tmp: Path

    private val reader = FlowHeaderReader { FlowHeader(appId = "com.empresa.app") }

    private fun build(options: RunOptions, configFile: Path = tmp.resolve("testigo.yaml")): String {
        val project = LoadedProject(ProjectConfig(app = AppConfig(artifact = "build/app.apk")), configFile, tmp)
        val plan = RunPlanner(tmp, reader).plan(options, project)
        return EquivalentCommand.build(plan)
    }

    @Test
    fun `incluye perfil, app y flows, y omite lo que ya da el perfil`() {
        val command = build(RunOptions(profile = Profile.EVIDENCIA, record = true))

        assertEquals("testigo run --profile evidencia --app build/app.apk --flows flows", command)
    }

    @Test
    fun `incluye los flags que cambian el perfil`() {
        val command = build(
            RunOptions(
                profile = Profile.DEV,
                record = true,
                network = NetworkMode.NONE,
                tags = listOf("smoke", "perfil"),
                device = "emulator-5554",
                ci = true,
            ),
        )

        assertEquals(
            "testigo run --profile dev --app build/app.apk --flows flows --tags smoke,perfil --record --network none" +
                " --device emulator-5554 --ci",
            command,
        )
    }

    @Test
    fun `incluye --redact o --no-redact solo si vino por flag`() {
        assertTrue(build(RunOptions(redact = true)).endsWith(" --redact"))
        assertTrue(build(RunOptions(redact = false)).endsWith(" --no-redact"))
        assertFalse(build(RunOptions()).contains("redact"))
    }

    @Test
    fun `agrega --config si testigo yaml no esta en la carpeta actual`() {
        val command = build(RunOptions(), configFile = tmp.resolve("suite/testigo.yaml"))

        assertTrue(command.contains("--config suite/testigo.yaml"), command)
    }

    @Test
    fun `nunca incluye valores de --env`() {
        val command = build(RunOptions(env = mapOf("PASSWORD" to "secreto")))

        assertFalse(command.contains("secreto"))
        assertFalse(command.contains("--env"))
    }

    @Test
    fun `entrecomilla argumentos con espacios o comillas`() {
        assertEquals("build/app.apk", EquivalentCommand.shellQuote("build/app.apk"))
        assertEquals("'mi app/app.apk'", EquivalentCommand.shellQuote("mi app/app.apk"))
        assertEquals("'it'\\''s'", EquivalentCommand.shellQuote("it's"))
    }
}
