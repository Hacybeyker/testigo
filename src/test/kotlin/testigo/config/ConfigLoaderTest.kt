package testigo.config

import testigo.TestigoException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ConfigLoaderTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `lee un testigo yaml completo`() {
        val config = ConfigLoader.parse(
            """
            version: 1
            app:
              artifact: build/app-qa-debug.aab
              package: auto
              signing:
                keystore: keys/qa.jks
                alias: qa
                password_env: TESTIGO_KS_PASS
            flows: flows/
            maestro:
              version: "1.2.3"
            defaults:
              profile: evidencia
            network:
              capture: none
              hosts: [api.qa.example.com]
            alerts:
              slow_ms: 2500
            redact:
              presets: [base, pii]
              json_keys: [policyNumber]
            evidence:
              dir: out/evidence/
              keep_raw: false
            """.trimIndent(),
            "testigo.yaml",
        )

        assertEquals("build/app-qa-debug.aab", config.app.artifact)
        assertEquals("TESTIGO_KS_PASS", config.app.signing?.passwordEnv)
        assertEquals(Profile.EVIDENCIA, config.defaults.profile)
        assertEquals(NetworkMode.NONE, config.network.capture)
        assertEquals(listOf("api.qa.example.com"), config.network.hosts)
        assertEquals(2500L, config.alerts.slowMs)
        assertEquals(listOf(RedactPreset.BASE, RedactPreset.PII), config.redact.presets)
        assertEquals("out/evidence/", config.evidence.dir)
    }

    @Test
    fun `un archivo vacio usa los valores por defecto`() {
        val config = ConfigLoader.parse("   \n", "testigo.yaml")

        assertEquals(ProjectConfig(), config)
        assertEquals(RedactPreset.entries.toList(), config.redact.presets)
    }

    @Test
    fun `una clave desconocida es un error con su linea`() {
        val error = assertThrows<TestigoException> {
            ConfigLoader.parse("version: 1\nnetwrok:\n  capture: chucker\n", "testigo.yaml")
        }

        assertTrue(error.message!!.contains("línea 2"), error.message)
    }

    @Test
    fun `un perfil inexistente es un error`() {
        assertThrows<TestigoException> {
            ConfigLoader.parse("defaults:\n  profile: produccion\n", "testigo.yaml")
        }
    }

    @Test
    fun `valida reglas que el parser no cubre`() {
        val file = tmp.resolve("testigo.yaml")
        file.writeText("version: 2\napp:\n  artifact: app.zip\nalerts:\n  slow_ms: 0\n")

        val error = assertThrows<TestigoException> { ConfigLoader.load(file, tmp) }

        val message = error.message!!
        assertTrue(message.contains("version: 2"), message)
        assertTrue(message.contains(".apk o .aab"), message)
        assertTrue(message.contains("slow_ms"), message)
    }

    @Test
    fun `busca testigo yaml hacia arriba desde la carpeta actual`() {
        tmp.resolve("testigo.yaml").writeText("flows: casos/\n")
        val nested = tmp.resolve("flows/perfil").createDirectories()

        val project = ConfigLoader.load(explicit = null, cwd = nested)

        assertEquals(tmp.resolve("testigo.yaml").toAbsolutePath().normalize(), project.file)
        assertEquals("casos/", project.config.flows)
    }

    @Test
    fun `sin testigo yaml se usan los valores por defecto`() {
        val project = ConfigLoader.load(explicit = null, cwd = tmp)

        assertNull(project.file)
        assertEquals(tmp, project.dir)
    }

    @Test
    fun `un --config inexistente es un error`() {
        assertThrows<TestigoException> { ConfigLoader.load(Path.of("no-existe.yaml"), tmp) }
    }
}
