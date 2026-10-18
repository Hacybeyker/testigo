package testigo.config

import testigo.TestigoException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ConfigEditorTest {

    @TempDir
    lateinit var tmp: Path

    private val suite = """
        |# Suite de ejemplo
        |version: 1
        |
        |maestro: { version: "2.11.0" }
        |
        |app:
        |  artifact: build/app-qa-debug.apk   # .apk o .aab, relativo a este archivo
        |  package: auto
        |
        |# Fija la versión de Maestro del equipo:
        |# maestro:
        |#   version: "x.y.z"
        |
        |network:
        |  capture: chucker        # none si la app no trae Chucker
        |  hosts:
        |    - api.qa.example.com
        |
        |evidence:
        |  dir: evidence/
        |
    """.trimMargin()

    @Test
    fun `cambia un valor y conserva comentarios, orden y el resto del archivo`() {
        val updated = ConfigEditor.apply(suite, listOf(ConfigEdit.artifact("../MiApp/app/build/outputs/apk/debug/app-debug.apk")))

        val expected = suite.replace(
            "  artifact: build/app-qa-debug.apk   # .apk o .aab, relativo a este archivo",
            "  artifact: ../MiApp/app/build/outputs/apk/debug/app-debug.apk   # .apk o .aab, relativo a este archivo",
        )
        assertEquals(expected, updated)
    }

    @Test
    fun `un valor más corto deja el comentario en su columna`() {
        val updated = ConfigEditor.apply(suite, listOf(ConfigEdit.artifact("a.apk")))

        // El comentario empezaba en la columna 37.
        assertTrue("  artifact: a.apk".padEnd(37) + "# .apk o .aab, relativo a este archivo" in updated, updated)
    }

    @Test
    fun `agrega la clave al bloque, o el bloque al final, si faltan`() {
        val updated = ConfigEditor.apply(suite, listOf(ConfigEdit.networkFormat(NetworkFormat.HAR), ConfigEdit.profile(Profile.EVIDENCIA)))

        assertTrue("  capture: chucker        # none si la app no trae Chucker\n  hosts:\n    - api.qa.example.com\n  format: har\n" in updated, updated)
        assertTrue(updated.endsWith("evidence:\n  dir: evidence/\n\ndefaults:\n  profile: evidencia\n"), updated)
    }

    @Test
    fun `edita un mapa en una línea y una clave de primer nivel`() {
        val updated = ConfigEditor.apply("version: 1\nmaestro: { version: \"2.11.0\" }\nflows: flows/   # carpeta\n", listOf(ConfigEdit.videoBitrate(6), ConfigEdit.flows("suite/flows/")))
        assertEquals("version: 1\nmaestro: { version: \"2.11.0\" }\nflows: suite/flows/   # carpeta\n\nvideo:\n  bitrate_mbps: 6\n", updated)

        val maestro = ConfigEditor.apply("maestro: { version: \"2.10.0\" }   # del equipo\n", listOf(ConfigEdit("maestro.version", "2.11.0", "\"2.11.0\"") { true }))
        assertEquals("maestro: { version: \"2.11.0\" }   # del equipo\n", maestro)
    }

    @Test
    fun `una clave vacía de testigo init se completa en su lugar`() {
        val text = "app:\n" + "  artifact:".padEnd(34) + "# pendiente: la ruta de tu APK o AAB\n  package: auto\n"

        val updated = ConfigEditor.apply(text, listOf(ConfigEdit.artifact("../app/app-debug.apk")))

        assertEquals("app:\n  artifact: ../app/app-debug.apk  # pendiente: la ruta de tu APK o AAB\n  package: auto\n", updated)
    }

    @Test
    fun `respeta CRLF y nunca convierte una lista o un bloque en un escalar`() {
        assertEquals("app:\r\n  artifact: b.apk\r\n", ConfigEditor.apply("app:\r\n  artifact: a.apk\r\n", listOf(ConfigEdit.artifact("b.apk"))))
        assertThrows<TestigoException> { ConfigEditor.apply("flows:\n  - a\n", listOf(ConfigEdit.flows("b/"))) }
        assertThrows<TestigoException> { ConfigEditor.apply("app: build.apk\n", listOf(ConfigEdit.artifact("b.apk"))) }
    }

    @Test
    fun `comillas solo cuando hacen falta`() {
        assertEquals("../My App/app-debug.apk", YamlScalar.string("../My App/app-debug.apk"))
        assertEquals("\"C:\\\\apps\\\\app.apk\"", YamlScalar.string("C:\\apps\\app.apk"))
        assertEquals("\"build #3.apk\"", YamlScalar.string("build #3.apk"))
        assertEquals("\"true\"", YamlScalar.string("true"))
        assertEquals("\"4\"", YamlScalar.string("4"))
        assertEquals("\"a: b\"", YamlScalar.string("a: b"))
    }

    @Test
    fun `las rutas quedan relativas a testigo yaml, salvo que suban demasiado`() {
        val suite = tmp.resolve("proyectos/MiApp-e2e")
        assertEquals("../MiApp/app/build/outputs/apk/debug/app-debug.apk", ConfigPaths.relative(suite, tmp.resolve("proyectos/MiApp/app/build/outputs/apk/debug/app-debug.apk")))
        assertEquals("flows", ConfigPaths.relative(suite, suite.resolve("flows")))
        assertEquals("/opt/builds/app.apk", ConfigPaths.relative(tmp.resolve("a/b/c/suite"), Path.of("/opt/builds/app.apk")))
    }

    @Test
    fun `guarda solo si el parser lee lo pedido, y sin dejar temporales`() {
        val file = tmp.resolve("testigo.yaml").also { it.writeText(suite) }
        val edit = ConfigEdit.artifact("nuevo.apk")

        ConfigEditor.save(file, listOf(edit)) { ProjectConfig(app = AppConfig(artifact = "nuevo.apk")) }
        assertTrue("  artifact: nuevo.apk" in file.readText())

        val before = file.readText()
        val error = assertThrows<TestigoException> { ConfigEditor.save(file, listOf(ConfigEdit.artifact("otro.apk"))) { ProjectConfig() } }
        assertTrue("Edítalo a mano: app.artifact: otro.apk" in error.message.orEmpty(), error.message)
        assertEquals(before, file.readText(), "si no queda como se pidió, no se escribe")

        assertThrows<TestigoException> { ConfigEditor.save(file, listOf(ConfigEdit.artifact("x.apk"))) { throw TestigoException("línea 3: inválido") } }
        assertEquals(before, file.readText())
        assertEquals(listOf("testigo.yaml"), tmp.listDirectoryEntries().map { it.fileName.toString() })
    }
}
