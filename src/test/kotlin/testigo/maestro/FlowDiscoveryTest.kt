package testigo.maestro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FlowDiscoveryTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `solo los yaml de casos de prueba son flows`() {
        assertTrue(FlowDiscovery.isFlowFile(Path.of("perfil/actualizar_datos.yaml")))
        assertTrue(FlowDiscovery.isFlowFile(Path.of("registro.yml")))
        assertFalse(FlowDiscovery.isFlowFile(Path.of("subflows/login.yaml")))
        assertFalse(FlowDiscovery.isFlowFile(Path.of("perfil/subflows/abrir.yaml")))
        assertFalse(FlowDiscovery.isFlowFile(Path.of("config.yaml")))
        assertFalse(FlowDiscovery.isFlowFile(Path.of(".borradores/wip.yaml")))
        assertFalse(FlowDiscovery.isFlowFile(Path.of("scripts/helper.js")))
    }

    @Test
    fun `separa el header de los comandos`() {
        val text = "appId: com.empresa.app\ntags: [smoke]\n---\n- launchApp\n"

        assertEquals("appId: com.empresa.app\ntags: [smoke]", FlowHeaderText.extract(text))
        assertEquals("", FlowHeaderText.extract("- launchApp\n"))
    }

    @Test
    fun `lee appId, name y tags e ignora el resto del header`() {
        val flow = tmp.resolve("registro.yaml")
        flow.writeText(
            """
            appId: ${'$'}{APP_ID}
            name: registro
            tags:
              - onboarding
              - smoke
            onFlowStart:
              - runFlow: subflows/login.yaml
            env:
              USER: ${'$'}{USER}
            ---
            - launchApp
            """.trimIndent(),
        )

        val header = YamlFlowHeaderReader().read(flow)

        assertEquals(FlowHeader(appId = "\${APP_ID}", name = "registro", tags = listOf("onboarding", "smoke")), header)
    }

    @Test
    fun `un header invalido se reporta sin detener el descubrimiento`() {
        val flows = tmp.resolve("flows").createDirectories()
        flows.resolve("bueno.yaml").writeText("appId: com.empresa.app\n---\n- launchApp\n")
        flows.resolve("roto.yaml").writeText("appId: [sin cerrar\n---\n- launchApp\n")

        val result = FlowDiscovery.discover(listOf(flows, tmp.resolve("no-existe")), YamlFlowHeaderReader())

        assertEquals(2, result.flows.size)
        assertEquals(1, result.unreadable.size)
        assertEquals(listOf(tmp.resolve("no-existe")), result.missingRoots)
    }

    @Test
    fun `include pide al menos un tag en comun y exclude descarta`() {
        val a = FlowFile(Path.of("a.yaml"), FlowHeader(tags = listOf("smoke", "perfil")))
        val b = FlowFile(Path.of("b.yaml"), FlowHeader(tags = listOf("regresion")))
        val c = FlowFile(Path.of("c.yaml"), FlowHeader())

        assertEquals(listOf(a, b, c), FlowDiscovery.filterByTags(listOf(a, b, c), emptyList(), emptyList()))
        assertEquals(listOf(a), FlowDiscovery.filterByTags(listOf(a, b, c), listOf("smoke"), emptyList()))
        assertEquals(listOf(b, c), FlowDiscovery.filterByTags(listOf(a, b, c), emptyList(), listOf("perfil")))
    }
}
