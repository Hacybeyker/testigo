package testigo.cli

import testigo.ExitCode
import testigo.artifact.Artifact
import testigo.artifact.ArtifactFormat
import testigo.artifact.ArtifactInfo
import testigo.config.NetworkMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class InitFlowTest {

    @TempDir
    lateinit var tmp: Path

    private class FakeAsker(private val picks: MutableList<Int?> = mutableListOf(), private val texts: MutableList<String?> = mutableListOf()) : Asker {
        val asked = mutableListOf<String>()
        val shown = mutableListOf<String>()

        override fun one(title: String, choices: List<Choice>, cursor: Int): Int? = picks.removeAt(0).also { asked += title }

        override fun many(title: String, choices: List<Choice>): List<Int>? = error("init no usa listas múltiples")

        override fun text(title: String): String? = texts.removeAt(0).also { asked += title }

        override fun answered(label: String, value: String) {
            shown += "$label: $value"
        }
    }

    private val suite get() = tmp.resolve("proyectos/MiApp-e2e")
    private val apk get() = tmp.resolve("proyectos/MiApp/app/build/outputs/apk/debug/app-debug.apk")
    private val output = mutableListOf<String>()

    private fun flow(asker: Asker?, chucker: Boolean? = true, builds: List<Path> = listOf(apk), readable: Boolean = true) = InitFlow(
        dir = suite,
        cwd = suite,
        asker = asker,
        inspect = { path ->
            if (!readable) null else ArtifactInfo(Artifact(path, ArtifactFormat.APK), "com.miempresa.app.qa", 70, "7.0.0-qa", true, "abc")
        },
        maestroVersion = { "2.11.0" },
        chucker = { chucker },
        builds = { builds },
        out = { output += it },
    )

    private fun createApk() {
        apk.parent.createDirectories()
        apk.writeText("apk")
    }

    @Test
    fun `elige un build encontrado, lee su package y detecta Chucker`() {
        createApk()
        val asker = FakeAsker(picks = mutableListOf(0))

        assertEquals(ExitCode.OK, flow(asker).run(app = null, network = null))

        val config = suite.resolve("testigo.yaml").readText()
        assertTrue("  artifact: ../MiApp/app/build/outputs/apk/debug/app-debug.apk" in config, config)
        assertTrue("  capture: chucker" in config)
        assertTrue("appId: com.miempresa.app.qa\n" in suite.resolve("flows/demo/abrir_app.yaml").readText())
        assertEquals(listOf("App que vas a probar (APK o AAB) · builds encontrados cerca de esta carpeta"), asker.asked)
        assertTrue(asker.shown.any { it.startsWith("Red: con Chucker") }, asker.shown.toString())
        assertTrue("Siguiente: testigo doctor" in output, output.toString())
    }

    @Test
    fun `sin build ni package queda pendiente y lo dice`() {
        val asker = FakeAsker(picks = mutableListOf(1), texts = mutableListOf(null, null))

        assertEquals(ExitCode.OK, flow(asker, chucker = null, builds = emptyList()).run(app = null, network = null))

        assertTrue(Regex("(?m)^  artifact: +# pendiente").containsMatchIn(suite.resolve("testigo.yaml").readText()))
        assertTrue("  capture: none" in suite.resolve("testigo.yaml").readText(), "respondió que no trae Chucker")
        assertTrue(output.any { "Falta la app" in it })
        assertTrue(output.any { "appId com.example.app" in it })
    }

    @Test
    fun `sin terminal no pregunta nada, y con --app lo usa`() {
        createApk()

        assertEquals(ExitCode.OK, flow(asker = null, chucker = false).run(app = apk, network = NetworkMode.CHUCKER))

        val config = suite.resolve("testigo.yaml").readText()
        assertTrue("  artifact: ../MiApp/app/build/outputs/apk/debug/app-debug.apk" in config)
        assertTrue("  capture: chucker" in config, "--network gana sobre lo detectado")
    }

    @Test
    fun `un --app que no existe es un error y cancelar no crea nada`() {
        assertEquals(ExitCode.ENVIRONMENT, flow(asker = null).run(app = tmp.resolve("no.apk"), network = null))
        createApk()
        assertEquals(ExitCode.CANCELLED, flow(FakeAsker(picks = mutableListOf(null))).run(app = null, network = null))
        assertTrue(!suite.resolve("testigo.yaml").exists())
    }
}
