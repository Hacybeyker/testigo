package testigo.network

import testigo.maestro.CommandsFixture
import testigo.maestro.StepTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StepNetworkTest {

    private val steps = StepTimeline.parse(CommandsFixture.JSON)

    /** El reloj del dispositivo va 100 ms adelante del de la máquina: [host] es la hora de la máquina. */
    private fun call(id: Long, method: String, path: String, host: Long, status: Int? = 200, took: Long? = 120) =
        NetworkCall(id, method, path, status, took, requestMs = host + 100, failed = status == null || status >= 400)

    private val calls = listOf(
        call(1, "GET", "/config", host = 1_500),
        call(2, "POST", "/auth/login", host = 3_500, took = 432),
        call(3, "GET", "/home", host = 20_000),
        call(4, "GET", "/poll", host = 30_000),
        call(6, "PUT", "/x", host = 43_100, status = 500),
        call(5, "GET", "/late", host = 60_000, status = null, took = null),
    )

    private val clock = ClockOffset(deviceMinusHostMs = 100, uncertaintyMs = 40)

    private fun owner(host: Long) = StepNetwork.owner(steps, host, slackMs = 540)?.sequence

    @Test
    fun `la llamada es del paso mas profundo que corria, no de su runFlow`() {
        assertEquals(2, owner(1_500), "launchApp")
        assertEquals(5, owner(3_500), "el tapOn del subflow, no el runFlow que lo contiene")
        assertEquals(6, owner(20_000), "el back")
        assertEquals(3, owner(30_000), "dentro del runFlow pero entre sus pasos: es del runFlow")
        assertEquals(7, owner(43_100))
    }

    @Test
    fun `una llamada pegada al borde cae en el paso mas cercano, y una lejana en ninguno`() {
        assertEquals(8, owner(43_253 + 400), "400 ms despues del ultimo paso, dentro de la incertidumbre")
        assertNull(owner(43_253 + 5_000))
        assertNull(owner(200), "antes del primer paso")
    }

    @Test
    fun `escribe cada paso con sus llamadas, el desfase de relojes y lo que quedo fuera`() {
        val text = StepNetwork.render("login", steps, calls, clock)!!

        assertTrue("# Llamadas de red por paso · login" in text, text)
        assertTrue("Reloj del dispositivo: +100 ms respecto del de la máquina (± 40 ms)" in text, text)
        assertTrue("paso 2 · launchApp · +0.0 s · 2.0 s · 1 llamada" in text, text)
        assertTrue("  paso 5 · tapOn \"Ingresar\" [confirmar] · +2.4 s · 8.0 s · 1 llamada" in text, text)
        assertTrue("    #2 POST /auth/login → 200 · 432 ms · +100 ms" in text, "las llamadas van bajo su paso, con el tiempo desde su inicio\n$text")
        assertTrue("  paso 4 · inputText · +2.0 s · 0.3 s · sin llamadas" in text, text)
        assertTrue("paso 8 · assertNotVisible \"Cargando\" [sin_spinner] · +42.2 s · 0.1 s · FALLÓ · sin llamadas" in text, text)
        assertTrue("#6 PUT /x → 500 · 120 ms" in text && "· error" in text, text)
        assertTrue("Fuera de los pasos (1)\n  #5 GET /late → sin respuesta" in text, text)
        assertFalse("Secreto123" in text, "el texto de inputText nunca sale")
    }

    @Test
    fun `cuenta las llamadas por paso para atribuirlas`() {
        val text = StepNetwork.render("login", steps, calls, clock)!!

        val perStep = Regex("paso (\\d+) · .*? · (\\d+|sin) (?:llamadas?|llamadas)").findAll(text)
            .associate { it.groupValues[1].toInt() to (it.groupValues[2].toIntOrNull() ?: 0) }
        assertEquals(mapOf(2 to 1, 3 to 1, 4 to 0, 5 to 1, 6 to 1, 7 to 1, 8 to 0), perStep)
    }

    @Test
    fun `sin medir el desfase avisa y supone cero`() {
        val text = StepNetwork.render("login", steps, listOf(call(1, "GET", "/config", host = 1_500).copy(requestMs = 1_500)), clock = null)!!

        assertTrue("No se pudo medir el desfase" in text, text)
        assertTrue("paso 2 · launchApp" in text && "1 llamada" in text, text)
    }

    @Test
    fun `sin pasos o sin llamadas no hay archivo`() {
        assertNull(StepNetwork.render("login", emptyList(), calls, clock))
        assertNull(StepNetwork.render("login", steps, emptyList(), clock))
    }
}
