package testigo.scaffold

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ScaffoldTest {

    @TempDir
    lateinit var tmp: Path

    private val answers = SuiteAnswers(
        artifact = "../MiApp/app/build/outputs/apk/debug/app-debug.apk",
        packageName = "com.miempresa.app",
        chucker = true,
        maestroVersion = "2.11.0",
    )

    @Test
    fun `crea testigo yaml, el flow de demo, env testigo y el gitignore`() {
        val results = Scaffold.write(tmp, answers)

        assertEquals(
            listOf("testigo.yaml", "flows/demo/abrir_app.yaml", ".env.testigo", ".gitignore"),
            results.map { tmp.relativize(it.path).toString() },
        )
        assertTrue(results.all { it.status == ScaffoldStatus.CREATED })
        val config = tmp.resolve("testigo.yaml").readText()
        assertTrue("  artifact: ../MiApp/app/build/outputs/apk/debug/app-debug.apk   # el APK o AAB que se prueba" in config, config)
        assertTrue("  version: \"2.11.0\"" in config)
        assertTrue("  capture: chucker" in config)
        assertTrue("flows: flows/" in config)
        val demo = tmp.resolve("flows/demo/abrir_app.yaml").readText()
        assertTrue("appId: com.miempresa.app\nname: abrir_app\ntags: [demo, smoke]\n---\n- launchApp\n- waitForAnimationToEnd\n" in demo, demo)
        assertEquals("# testigo: los usuarios de prueba y la evidencia no se suben al repo.\n.env.testigo\nevidence/\n", tmp.resolve(".gitignore").readText())
        assertTrue(tmp.resolve(".env.testigo").readText().lines().filter { it.isNotBlank() }.all { it.startsWith("#") }, "sin variables reales")
    }

    @Test
    fun `sin app ni package deja todo pendiente y sin Chucker`() {
        Scaffold.write(tmp, answers.copy(artifact = null, packageName = null, chucker = false))

        val config = tmp.resolve("testigo.yaml").readText()
        assertTrue(Regex("(?m)^  artifact: +# pendiente").containsMatchIn(config), config)
        assertTrue("  capture: none" in config)
        assertTrue("appId: com.example.app        # cámbialo" in tmp.resolve("flows/demo/abrir_app.yaml").readText())
    }

    @Test
    fun `no pisa nada y completa lo que falta`() {
        tmp.resolve("testigo.yaml").writeText("version: 1\n")
        tmp.resolve("flows/login").createDirectories().resolve("login.yaml").writeText("appId: x\n---\n- launchApp\n")
        tmp.resolve(".gitignore").writeText("build/\n.env.testigo")

        val results = Scaffold.write(tmp, answers).associate { tmp.relativize(it.path).toString() to it.status }

        assertEquals(ScaffoldStatus.KEPT, results["testigo.yaml"])
        assertEquals(ScaffoldStatus.KEPT, results["flows"])
        assertEquals(ScaffoldStatus.CREATED, results[".env.testigo"])
        assertEquals(ScaffoldStatus.UPDATED, results[".gitignore"])
        assertEquals("version: 1\n", tmp.resolve("testigo.yaml").readText())
        assertEquals("build/\n.env.testigo\n\n# testigo: los usuarios de prueba y la evidencia no se suben al repo.\nevidence/\n", tmp.resolve(".gitignore").readText())

        val again = Scaffold.write(tmp, answers)
        assertTrue(again.all { it.status == ScaffoldStatus.KEPT }, "una segunda vez no cambia nada")
        assertNull(Scaffold.gitignore("/evidence\n/.env.testigo\n"))
    }
}
