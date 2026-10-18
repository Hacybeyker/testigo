package testigo.policy

import testigo.TestigoException
import testigo.ExitCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MaestroPolicyTest {

    @Test
    fun `el entorno fija las variables local-only y quita las claves de IA y Cloud`() {
        val env = mutableMapOf(
            "PATH" to "/usr/bin",
            "MAESTRO_API_URL" to "https://api.copilot.mobile.dev",
            "MAESTRO_CLI_AI_KEY" to "sk-algo",
            "MAESTRO_CLOUD_API_KEY" to "otra",
        )

        MaestroEnvironment.applyTo(env)

        assertEquals("/usr/bin", env["PATH"])
        MaestroEnvironment.REQUIRED.forEach { (name, value) -> assertEquals(value, env[name], name) }
        assertFalse("MAESTRO_CLI_AI_KEY" in env)
        assertFalse("MAESTRO_CLOUD_API_KEY" in env)
    }

    @Test
    fun `avisa que variables del shell se van a quitar`() {
        val current = mapOf("MAESTRO_CLOUD_API_KEY" to "x", "MAESTRO_CLI_AI_KEY" to "y", "HOME" to "/Users/qa")

        assertEquals(listOf("MAESTRO_CLI_AI_KEY", "MAESTRO_CLOUD_API_KEY"), MaestroEnvironment.removedFrom(current))
    }

    @Test
    fun `solo se permiten maestro test y maestro --version`() {
        MaestroProcess.check(listOf("test", "flows/caso.yaml"))
        MaestroProcess.check(listOf("--device", "emulator-5554", "test", "--test-output-dir", "out", "flows/caso.yaml"))
        MaestroProcess.check(listOf("--version"))
    }

    @Test
    fun `los subcomandos y flags que envian datos terminan con codigo 3`() {
        val rejected = listOf(
            listOf("cloud", "--app-file", "app.apk", "flows/"),
            listOf("login"),
            listOf("--device", "emulator-5554", "record", "flows/caso.yaml"),
            listOf("test", "--analyze", "flows/caso.yaml"),
            listOf("test", "--api-key=abc", "flows/caso.yaml"),
            emptyList(),
        )
        rejected.forEach { args ->
            val error = assertThrows(TestigoException::class.java) { MaestroProcess.check(args) }
            assertEquals(ExitCode.POLICY, error.exitCode, args.toString())
        }
    }

    @Test
    fun `el proceso de Maestro siempre lleva el entorno local-only`() {
        val env = MaestroProcess.builder("maestro", listOf("test", "flows/caso.yaml")).environment()

        MaestroEnvironment.REQUIRED.forEach { (name, value) -> assertEquals(value, env[name], name) }
    }
}
