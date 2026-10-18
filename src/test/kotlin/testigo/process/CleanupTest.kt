package testigo.process

import testigo.maestro.FlowFile
import testigo.maestro.FlowHeader
import testigo.video.Recorder
import testigo.video.VideoSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class CleanupTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `al cortarse ejecuta lo pendiente de lo ultimo a lo primero, aunque una accion falle`() {
        Cleanup.runAll()
        val order = mutableListOf<String>()
        Cleanup.register("a") { order += "a" }
        Cleanup.register("b") { error("falla") }
        Cleanup.register("c") { order += "c" }
        Cleanup.register("d") { order += "d" }
        Cleanup.unregister("d")

        Cleanup.runAll()

        assertEquals(listOf("c", "a"), order)
        assertEquals(0, Cleanup.pending())
    }

    private class IdleRecorder : Recorder {
        override fun start() = Unit

        override fun stop() = Unit

        override fun pull(dir: Path): List<Path> = emptyList()
    }

    @Test
    fun `si la corrida se corta, el wrapper de video no queda junto al flow`() {
        Cleanup.runAll()
        val flow = tmp.resolve("flows").createDirectories().resolve("home.yaml").also { it.writeText("appId: com.empresa.app\n---\n- launchApp\n") }
        val session = VideoSession(IdleRecorder(), FlowFile(flow, FlowHeader(appId = "com.empresa.app")), "com.empresa.app")
        assertTrue(session.wrapper.exists())

        Cleanup.runAll()

        assertFalse(session.wrapper.exists(), "Ctrl+C a mitad del flow")
    }

    @Test
    fun `al terminar bien no queda nada pendiente`() {
        Cleanup.runAll()
        val flow = tmp.resolve("flows").createDirectories().resolve("home.yaml").also { it.writeText("appId: com.empresa.app\n---\n- launchApp\n") }

        VideoSession(IdleRecorder(), FlowFile(flow, FlowHeader()), "com.empresa.app").close()

        assertEquals(0, Cleanup.pending())
    }
}
