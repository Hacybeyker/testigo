package testigo.cli

import testigo.maestro.FlowFile
import testigo.maestro.FlowHeader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

class FlowListRendererTest {

    @TempDir
    lateinit var tmp: Path

    private fun flows(): Pair<Path, List<FlowFile>> {
        val root = tmp.resolve("suite/flows").createDirectories()
        return root to listOf(
            FlowFile(root.resolve("home.yaml"), FlowHeader(tags = listOf("smoke"))),
            FlowFile(root.resolve("perfil/actualizar_datos.yaml"), FlowHeader(name = "Actualizar datos", tags = listOf("perfil", "regresion"))),
            FlowFile(root.resolve("perfil/cerrar_sesion.yaml"), FlowHeader(name = "cerrar_sesion", tags = listOf("perfil"))),
        )
    }

    @Test
    fun `lista archivo, nombre y tags, con el conteo por tag`() {
        val (root, flows) = flows()

        val text = FlowListRenderer.render(listOf(root), "flows en testigo.yaml", flows, flows, emptyList(), emptyList(), tmp.resolve("suite"))

        assertEquals(
            """
            Flows · flows/ (flows en testigo.yaml) · 3

              home.yaml                      home               [smoke]
              perfil/actualizar_datos.yaml   Actualizar datos   [perfil, regresion]
              perfil/cerrar_sesion.yaml      cerrar_sesion      [perfil]

            Tags  perfil 2 · regresion 1 · smoke 1
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `con tags muestra cuantos quedan de cuantos, y sin coincidencias dice ninguno`() {
        val (root, flows) = flows()
        val perfil = flows.filter { "perfil" in it.header.tags && "regresion" !in it.header.tags }

        val text = FlowListRenderer.render(listOf(root), "por defecto", flows, perfil, listOf("perfil"), listOf("regresion"), tmp.resolve("suite"))
        assertEquals("Flows · flows/ (por defecto) · 1 de 3 (tags: perfil · sin: regresion)", text.lines().first())
        assertEquals("  perfil/cerrar_sesion.yaml   cerrar_sesion   [perfil]", text.lines()[2])

        val none = FlowListRenderer.render(listOf(root), "por defecto", flows, emptyList(), listOf("pagos"), emptyList(), tmp.resolve("suite"))
        assertEquals(listOf("Flows · flows/ (por defecto) · 0 de 3 (tags: pagos)", "", "  (ninguno)"), none.lines().filter { true }.dropLastWhile { it.isEmpty() })
    }
}
