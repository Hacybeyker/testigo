package testigo.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import testigo.BuildInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Prueba el CLI completo de punta a punta, como lo usaría un dev. */
class RunCommandTest {

    @TempDir
    lateinit var tmp: Path

    private fun cli() = TestigoCommand().subcommands(RunCommand())

    private fun suite(): Path {
        tmp.resolve("testigo.yaml").writeText(
            """
            version: 1
            app:
              artifact: build/app-qa-debug.apk
            defaults:
              profile: evidencia
            maestro:
              version: "1.0.0"
            """.trimIndent(),
        )
        tmp.resolve(".env.testigo").writeText("USER=qa@example.com\nPASSWORD=secreto\n")
        val flows = tmp.resolve("flows/perfil").createDirectories()
        flows.resolve("actualizar_datos.yaml").writeText("appId: com.empresa.app\ntags: [perfil, smoke]\n---\n- launchApp\n")
        return tmp.resolve("testigo.yaml")
    }

    @Test
    fun `dry-run imprime el plan y el comando equivalente`() {
        val config = suite()

        val result = cli().test("run --dry-run --config $config --tags smoke")

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.stdout.contains("simulación (--dry-run)"), result.stdout)
        assertTrue(result.stdout.contains("evidencia"), result.stdout)
        assertTrue(result.stdout.contains("actualizar_datos.yaml"), result.stdout)
        assertTrue(result.stdout.contains("USER, PASSWORD"), result.stdout)
        assertTrue(result.stdout.contains("Comando equivalente"), result.stdout)
        assertTrue(result.stdout.contains("Local-only") && result.stdout.contains("sin violaciones"), result.stdout)
        assertFalse(result.output.contains("secreto"), "nunca se imprimen valores de variables")
    }

    @Test
    fun `un flow con assertWithAI termina con codigo 3 sin ejecutar Maestro`() {
        val config = suite()
        tmp.resolve("flows/perfil/con_ia.yaml").writeText(
            "appId: com.empresa.app\ntags: [perfil]\n---\n- launchApp\n- assertWithAI:\n    assertion: \"Se ve la pantalla de inicio\"\n",
        )

        val result = cli().test("run --config $config")

        assertEquals(3, result.statusCode, result.output)
        assertTrue(result.stdout.contains("con_ia.yaml:5"), result.stdout)
        assertTrue(result.stderr.contains("política local-only"), result.stderr)
    }

    @Test
    fun `un subflow sin configuracion termina con codigo 2 tambien en --dry-run y dice que agregar`() {
        val config = suite()
        tmp.resolve("flows/subflows").createDirectories().resolve("login.yaml").writeText("- tapOn: \"Entrar\"\n")
        tmp.resolve("flows/perfil/actualizar_datos.yaml")
            .writeText("appId: com.empresa.app\ntags: [perfil, smoke]\n---\n- launchApp\n- runFlow: ../subflows/login.yaml\n")

        val result = cli().test("run --dry-run --config $config")

        assertEquals(2, result.statusCode, result.output)
        assertTrue(result.stdout.contains("Formato de Maestro: 1 archivo que no acepta"), result.stdout)
        assertTrue(result.stdout.contains("flows/subflows/login.yaml  no empieza con la configuración"), result.stdout)
        assertTrue(result.stdout.contains("    Agrega al inicio:\n      appId: com.empresa.app\n      ---"), result.stdout)
        assertTrue(result.stderr.contains("Maestro no aceptaría un archivo de flows"), result.stderr)
    }

    @Test
    fun `sin --dry-run el preflight termina con codigo 2 si la app no existe`() {
        val config = suite()

        val result = cli().test("run --config $config")

        assertEquals(2, result.statusCode, result.output)
        assertTrue(result.stderr.contains("La app no existe"), result.stderr)
    }

    @Test
    fun `un error de configuracion termina con codigo 2 y sin stacktrace`() {
        tmp.resolve("testigo.yaml").writeText("version: 1\n")

        val result = cli().test("run --dry-run --config ${tmp.resolve("testigo.yaml")}")

        assertEquals(2, result.statusCode)
        assertTrue(result.stderr.startsWith("Error: Falta la app"), result.stderr)
    }

    @Test
    fun `--save guarda los flags en testigo yaml y conserva el resto, pero no con --dry-run`() {
        val config = suite()
        val before = config.readText()

        val dry = cli().test("run --dry-run --save --config $config --profile smoke")
        assertEquals(0, dry.statusCode, dry.output)
        assertTrue(dry.stdout.contains("--dry-run: no guardé nada"), dry.stdout)
        assertEquals(before, config.readText())

        // Sin --dry-run guarda antes del preflight, que aquí termina con 2 porque la app no existe.
        val result = cli().test("run --save --config $config --profile smoke --video-bitrate 6")

        assertEquals(2, result.statusCode, result.output)
        assertTrue(result.stdout.contains("✔ Guardado en testigo.yaml: defaults.profile, video.bitrate_mbps"), result.stdout)
        assertEquals(
            before.replace("profile: evidencia", "profile: smoke") + "\n\nvideo:\n  bitrate_mbps: 6\n",
            config.readText(),
        )
    }

    @Test
    fun `--version muestra la version`() {
        val result = cli().test("--version")

        assertTrue(result.stdout.contains(BuildInfo.VERSION), result.stdout)
    }
}
