package testigo.cli

import testigo.config.AppConfig
import testigo.config.DefaultsConfig
import testigo.config.LoadedProject
import testigo.config.NetworkConfig
import testigo.config.NetworkMode
import testigo.config.Profile
import testigo.config.ProjectConfig
import testigo.config.RunOptions
import testigo.config.RunPlanner
import testigo.maestro.FlowHeader
import testigo.maestro.FlowHeaderReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

class InteractiveRunTest {

    @TempDir
    lateinit var tmp: Path

    /** Responde con lo que se le indique y anota cada pregunta. */
    private class FakeAsker(
        private val one: (String, List<Choice>, Int) -> Int? = { _, _, cursor -> cursor },
        private val many: (String, List<Choice>) -> List<Int>? = { _, choices -> choices.indices.filter { choices[it].selected } },
        private val text: String? = null,
    ) : Asker {
        val asked = mutableListOf<String>()
        val shown = mutableListOf<Pair<String, String>>()

        override fun one(title: String, choices: List<Choice>, cursor: Int): Int? = one.invoke(title, choices, cursor).also { asked += title }

        override fun many(title: String, choices: List<Choice>): List<Int>? = many.invoke(title, choices).also { asked += title }

        override fun text(title: String): String? = text.also { asked += title }

        override fun answered(label: String, value: String) {
            shown += label to value
        }
    }

    private val now = Instant.parse("2026-10-16T20:00:00Z")

    /** Suite con 3 flows con tags y una app con dos builds (más uno de tests) en `build/outputs`. */
    private fun project(
        artifact: String? = "../app/build/outputs/apk/qa/app-qa.apk",
        profile: Profile? = Profile.EVIDENCIA,
        network: NetworkMode? = null,
    ): LoadedProject {
        val suite = tmp.resolve("suite").createDirectories()
        val flows = suite.resolve("flows").createDirectories()
        listOf("home.yaml", "perfil/actualizar_datos.yaml", "perfil/cerrar_sesion.yaml").forEach {
            flows.resolve(it).also { path -> path.parent.createDirectories() }.writeText("appId: com.empresa.app\n---\n- launchApp\n")
        }
        val outputs = tmp.resolve("app/build/outputs")
        build(outputs.resolve("apk/qa/app-qa.apk"), hoursAgo = 3)
        build(outputs.resolve("bundle/qaDebug/app-qa-debug.aab"), hoursAgo = 1)
        build(outputs.resolve("apk/androidTest/qa/app-qa-androidTest.apk"), hoursAgo = 1)
        return LoadedProject(
            ProjectConfig(
                app = AppConfig(artifact = artifact),
                defaults = DefaultsConfig(profile = profile),
                network = NetworkConfig(capture = network),
            ),
            suite.resolve("testigo.yaml"),
            suite,
        )
    }

    private fun build(path: Path, hoursAgo: Long) {
        path.parent.createDirectories()
        path.writeText("x")
        path.setLastModifiedTime(FileTime.from(now.minusSeconds(hoursAgo * 3600)))
    }

    private val reader = FlowHeaderReader { path ->
        val tags = if ("perfil" in path.toString()) listOf("perfil") else listOf("smoke")
        FlowHeader(appId = "com.empresa.app", name = path.name.removeSuffix(".yaml"), tags = tags)
    }

    @Test
    fun `pregunta que capturar, flows y app, y el plan equivale al comando impreso`() {
        val project = project()
        val asker = FakeAsker(
            many = { title, choices ->
                if (title.startsWith("Qué capturar")) {
                    assertEquals(
                        listOf(
                            "Video   graba la pantalla de cada flow",
                            "Red     requests y responses del backend, desde Chucker",
                            "Logs    logcat de la app y crashes",
                        ),
                        choices.map { it.title },
                    )
                    assertEquals(listOf(true, true, true), choices.map { it.selected }, "parte del perfil evidencia de testigo.yaml")
                    listOf(1, 2)
                } else {
                    assertEquals("Flows a ejecutar · los .yaml de flows/ (flows en testigo.yaml)", title)
                    assertEquals(
                        listOf("perfil/actualizar_datos.yaml   [perfil]", "perfil/cerrar_sesion.yaml      [perfil]", "home.yaml                      [smoke]"),
                        choices.map { it.title },
                    )
                    listOf(0)
                }
            },
            one = { title, choices, cursor ->
                assertEquals("App (APK o AAB) · builds de ${tmp.resolve("app/build/outputs")}", title)
                assertEquals(
                    listOf(
                        "apk/qa/app-qa.apk" + " ".repeat(17) + "APK · hace 3 h · 0 MB · la de testigo.yaml",
                        "bundle/qaDebug/app-qa-debug.aab   AAB · hace 1 h · 0 MB",
                    ),
                    choices.map { it.title },
                )
                assertEquals(0, cursor, "la de testigo.yaml va primero")
                1
            },
        )

        val options = InteractiveRun(asker, reader, tmp.resolve("suite"), now = { now }).ask(RunOptions(), project)!!

        assertEquals(3, asker.asked.size)
        assertEquals("Captura" to "red, logs", asker.shown[0])
        assertEquals(false, options.record)
        assertEquals(NetworkMode.CHUCKER, options.network)
        assertEquals(true, options.logs)
        assertNull(options.profile, "el perfil no se pregunta")
        assertEquals(listOf(tmp.resolve("suite/flows/perfil/actualizar_datos.yaml")), options.flows)
        assertEquals(tmp.resolve("app/build/outputs/bundle/qaDebug/app-qa-debug.aab"), options.app)

        val plan = RunPlanner(tmp.resolve("suite"), reader).plan(options, project)
        assertEquals(
            "testigo run --profile evidencia --app ${tmp.resolve("app/build/outputs/bundle/qaDebug/app-qa-debug.aab")} " +
                "--flows flows/perfil/actualizar_datos.yaml --no-record",
            EquivalentCommand.build(plan),
        )
        assertEquals(listOf("actualizar_datos"), plan.flows.map { it.header.name })
    }

    @Test
    fun `sin cambios usa la carpeta de flows y la app de testigo yaml, y una sola app no se pregunta`() {
        val project = project(profile = null)
        tmp.resolve("app/build/outputs/bundle").toFile().deleteRecursively()
        val asker = FakeAsker()

        val options = InteractiveRun(asker, reader, tmp.resolve("suite"), now = { now }).ask(RunOptions(), project)!!

        assertEquals(listOf("Qué capturar en cada flow", "Flows a ejecutar · los .yaml de flows/ (flows en testigo.yaml)"), asker.asked)
        assertEquals("Captura" to "red, logs", asker.shown[0], "sin perfil en testigo.yaml parte de dev")
        assertEquals(emptyList<Path>(), options.flows)
        assertNull(options.app)
        assertEquals("Flows" to "3 de 3: perfil/actualizar_datos.yaml, perfil/cerrar_sesion.yaml, home.yaml", asker.shown[1])
        val plan = RunPlanner(tmp.resolve("suite"), reader).plan(options, project)
        assertTrue(EquivalentCommand.build(plan).endsWith("--flows flows"), EquivalentCommand.build(plan))
    }

    @Test
    fun `con network capture none no ofrece la red`() {
        val asker = FakeAsker(many = { title, choices ->
            if (title.startsWith("Qué capturar")) assertEquals(listOf("Video", "Logs"), choices.map { it.title.substringBefore(' ') })
            choices.indices.toList()
        })

        val options = InteractiveRun(asker, reader, tmp.resolve("suite"), now = { now }).ask(RunOptions(), project(network = NetworkMode.NONE))!!

        assertNull(options.network)
        assertEquals(true, options.record)
    }

    @Test
    fun `sin app en testigo yaml pide la ruta, y cancelar o no elegir flows no ejecuta nada`() {
        val noApp = project(artifact = null)
        val typed = FakeAsker(text = "../app/build/outputs/apk/qa/app-qa.apk")

        val options = InteractiveRun(typed, reader, tmp.resolve("suite")).ask(RunOptions(), noApp)!!
        assertEquals(tmp.resolve("app/build/outputs/apk/qa/app-qa.apk"), options.app)
        assertEquals("Ruta del APK o AAB (no hay app.artifact en testigo.yaml)", typed.asked.last())

        val missing = project(artifact = "../app/build/app-vieja.apk")
        tmp.resolve("app/build/outputs").toFile().deleteRecursively()
        val askedAgain = FakeAsker(text = "app.apk")
        InteractiveRun(askedAgain, reader, tmp.resolve("suite")).ask(RunOptions(), missing)
        assertEquals("Ruta del APK o AAB (no existe ../app/build/app-vieja.apk)", askedAgain.asked.last())

        assertNull(InteractiveRun(FakeAsker(many = { _, _ -> null }), reader, tmp).ask(RunOptions(), project()))
        val noFlows = FakeAsker(many = { title, choices -> if (title.startsWith("Flows")) emptyList() else choices.indices.toList() })
        assertNull(InteractiveRun(noFlows, reader, tmp).ask(RunOptions(), project()))
    }

    @Test
    fun `ofrece guardar la app y el perfil elegidos, y nada si no cambió`() {
        val noApp = project(artifact = null, profile = null)
        val questions = InteractiveRun(FakeAsker(), reader, tmp.resolve("suite"))
        val chosen = RunOptions(app = tmp.resolve("app/build/outputs/apk/qa/app-qa.apk"), record = true, network = NetworkMode.CHUCKER, logs = true)

        val edits = questions.saveable(chosen, noApp)

        assertEquals(listOf("app.artifact: ../app/build/outputs/apk/qa/app-qa.apk", "defaults.profile: evidencia"), edits.map { it.toString() })

        // La app ya es la de testigo.yaml y la captura es la del perfil por defecto (dev): nada que guardar.
        val same = RunOptions(app = tmp.resolve("app/build/outputs/apk/qa/app-qa.apk"), record = false, network = NetworkMode.CHUCKER, logs = true)
        assertEquals(emptyList<String>(), questions.saveable(same, project(profile = null)).map { it.toString() })

        // Una captura que no es la de ningún perfil (video sin logs) no se guarda; una app que no existe tampoco.
        val odd = RunOptions(app = tmp.resolve("no-existe.apk"), record = true, network = NetworkMode.NONE, logs = false)
        assertEquals(emptyList<String>(), questions.saveable(odd, noApp).map { it.toString() })
        assertEquals(emptyList<String>(), questions.saveable(chosen, noApp.copy(file = null)).map { it.toString() }, "sin testigo.yaml no hay dónde")

        val asker = FakeAsker(one = { _, choices, _ ->
            assertEquals("Sí, guardar app.artifact: ../app/build/outputs/apk/qa/app-qa.apk · defaults.profile: evidencia", choices[0].title)
            0
        })
        assertEquals(true, InteractiveRun(asker, reader, tmp).askSave(edits, "testigo.yaml"))
        assertEquals("¿Guardo esto en testigo.yaml para la próxima vez?", asker.asked.single())
        assertNull(InteractiveRun(FakeAsker(one = { _, _, _ -> null }), reader, tmp).askSave(edits, "testigo.yaml"))
    }
}
