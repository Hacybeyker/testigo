package testigo.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import testigo.BuildInfo
import testigo.maestro.CommandsFixture
import testigo.maestro.StepTimeline
import testigo.network.ClockOffset
import testigo.network.NetworkCall
import testigo.network.StepNetwork
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Lo que dice la documentación tiene que funcionar: las recetas se corren y las versiones se comparan con la del build. */
class DocsTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `la receta de awk de la skill cuenta desde el paso hasta el final del flow y no las llamadas fuera de los pasos`() {
        val recipe = Path.of("skills/testigo-flows/references/testigo.md").readText().lines()
            .map { it.trim() }
            .single { it.startsWith("awk '") && it.contains("confirmar_pago") }
        // El flow del fixture, con el paso del tapOn etiquetado como en la receta. El reloj del dispositivo va 100 ms adelante.
        val steps = StepTimeline.parse(CommandsFixture.JSON).map { if (it.label == "confirmar") it.copy(label = "confirmar_pago") else it }
        fun pago(id: Long, host: Long) = NetworkCall(id, "POST", "/pagos", 200, 120, requestMs = host + 100, failed = false)
        val calls = listOf(
            pago(1, host = 1_500), // en launchApp, antes del paso etiquetado: no cuenta
            pago(2, host = 3_500), // dentro del paso etiquetado: cuenta
            pago(3, host = 43_100), // en un paso posterior: cuenta
            pago(4, host = 60_000), // fuera de todos los pasos, mucho después de terminar: no cuenta
        )
        val steplog = tmp.resolve("network-steps.txt")
        steplog.writeText(StepNetwork.render("pago", steps, calls, ClockOffset(100, 40))!!)
        assertTrue("Fuera de los pasos (1)" in steplog.readText(), "el fixture debe dejar una llamada fuera de los pasos:\n${steplog.readText()}")

        val command = recipe.replace("evidence/<corrida>/<flow>/network-steps.txt", steplog.toString())
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).redirectOutput(tmp.resolve("awk.txt").toFile()).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))

        assertEquals("2", tmp.resolve("awk.txt").readText().trim(), "desde confirmar_pago hasta el final del flow hay 2 POST /pagos; el de «Fuera de los pasos» no cuenta")
    }

    @Test
    fun `el comando para instalar el archivo recien compilado apunta a la version actual`() {
        for (doc in listOf("README.md", "docs/GUIA.md")) {
            val versions = Regex("""build/distributions/testigo-(\S+?)\.tar\.gz""").findAll(Path.of(doc).readText()).map { it.groupValues[1] }.toSet()

            assertEquals(setOf(BuildInfo.VERSION), versions, "$doc: el archivo que deja ./gradlew build es testigo-${BuildInfo.VERSION}.tar.gz")
        }
    }
}
