package testigo.logs

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import testigo.TestigoException
import testigo.maestro.FlowExecutor
import testigo.maestro.FlowFile
import testigo.maestro.FlowHeader
import testigo.maestro.MaestroCli
import testigo.process.Cleanup
import testigo.process.CommandResult
import testigo.process.CommandRunner
import testigo.redact.RedactRules
import testigo.redact.Redactor
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration

/**
 * Lo que pasa con el dispositivo cuando una corrida muere a la mitad de una captura de logcat: el buffer agrandado y el
 * `adb logcat` abierto no deben quedar ahí, ni confundir a la corrida siguiente.
 */
@DisabledOnOs(OS.WINDOWS)
class LogCaptureRecoveryTest {

    @TempDir
    lateinit var tmp: Path

    private val original = mapOf("main" to "256K", "system" to "256K", "crash" to "256K")
    private val processes = mutableListOf<Process>()
    private val store get() = CaptureStore(tmp.resolve("run/logcat-NJ4"))

    @AfterEach
    fun cleanUp() {
        Cleanup.runAll()
        processes.forEach { it.destroyForcibly() }
    }

    /** logd simulado: `logcat -g` dice el tamaño de cada buffer y `logcat -G` lo cambia. */
    private class FakeLogd(initial: Map<String, String>) : CommandRunner {
        val sizes = initial.toMutableMap()
        val commands = mutableListOf<List<String>>()

        /** adb no contesta a `logcat -G`: lanza como lo hace `ProcessCommandRunner` al agotar el tiempo. */
        var unreachableOnSet = false

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            val args = command.drop(3)
            return when {
                "-g" in args -> CommandResult(
                    0,
                    sizes.entries.joinToString("\n") { (name, size) ->
                        "$name: ring buffer is ${size.dropLast(1)} ${size.last()}iB (0 B consumed), max entry is 5120 B, max payload is 4068 B"
                    },
                    "",
                )
                "-G" in args -> {
                    if (unreachableOnSet) throw TestigoException("adb no terminó en 15s.")
                    val names = if ("-b" in args) args[args.indexOf("-b") + 1].split(",") else sizes.keys.toList()
                    names.forEach { sizes[it] = args.last() }
                    CommandResult(0, "", "")
                }
                else -> CommandResult(0, "", "")
            }
        }

        fun enlarged() = sizes.replaceAll { _, _ -> "16M" }
    }

    /** Un proceso que se hace pasar por `adb -s NJ4 logcat`: tiene esos argumentos en su línea de comando. */
    private fun fakeLogcat(): Process =
        ProcessBuilder("sh", "-c", "sleep 60; true", "adb", "-s", "NJ4", "logcat").start().also { processes += it }

    private fun sleeper(): Process = ProcessBuilder("sleep", "60").start().also { processes += it }

    /** Una captura sobre NJ4; `raws` recoge dónde escribe el logcat crudo. */
    private fun capture(logd: CommandRunner, raws: MutableList<Path> = mutableListOf(), launched: MutableList<Process> = mutableListOf()) =
        LogCapture(
            adb = Path.of("/sdk/platform-tools/adb"),
            serial = "NJ4",
            packageName = "com.empresa.app",
            uid = 10234,
            sdk = 33,
            runner = logd,
            stateDir = tmp.resolve("run"),
            startProcess = { _, output ->
                raws.add(output) // `+=` agregaría las partes de la ruta: un Path también es Iterable<Path>
                output.writeText("10-16 15:10:00.000  5555  5555 I MiApp: hola\n")
                fakeLogcat().also { launched += it }
            },
        )

    private fun records() = store.entries().map { it.first.fileName.toString() }

    @Test
    fun `si Maestro lanza una excepcion devuelve los buffers, cierra logcat y borra lo que dejo`() {
        val logd = FakeLogd(original)
        val raws = mutableListOf<Path>()
        val launched = mutableListOf<Process>()
        val flowPath = tmp.resolve("flows").createDirectories().resolve("uno.yaml").also { it.writeText("appId: com.empresa.app\n---\n- launchApp\n") }
        val flow = FlowFile(flowPath, FlowHeader(appId = "com.empresa.app", name = null))
        // Un maestro que no existe: ProcessBuilder.start() lanza IOException, como cualquier fallo de Maestro que no es un flow fallido.
        val executor = FlowExecutor(MaestroCli(tmp.resolve("no-hay-maestro")), logs = { capture(logd, raws, launched) })

        assertThrows(IOException::class.java) {
            executor.run(listOf(flow), "NJ4", emptyMap(), tmp.resolve("evidence"), "com.empresa.app")
        }

        assertEquals(original, logd.sizes, "los buffers vuelven a como estaban: la corrida no llegó a finish()")
        assertTrue(launched.single().waitFor(5, TimeUnit.SECONDS), "el adb logcat se cerró")
        assertFalse(raws.single().exists(), "el logcat crudo no queda en /tmp")
        assertEquals(emptyList<String>(), records(), "no queda nada anotado")
    }

    @Test
    fun `con Ctrl+C devuelve los buffers, cierra logcat y borra lo que dejo`() {
        val logd = FakeLogd(original)
        val raws = mutableListOf<Path>()
        val launched = mutableListOf<Process>()
        val capture = capture(logd, raws, launched)

        capture.start(tmp)
        assertEquals(setOf("16M"), logd.sizes.values.toSet(), "durante el flow los buffers están agrandados")
        assertEquals(1, records().size, "y la corrida lo tiene anotado")

        Cleanup.runAll() // lo que corre el shutdown hook de la JVM al cortar con Ctrl+C

        assertEquals(original, logd.sizes)
        assertTrue(launched.single().waitFor(5, TimeUnit.SECONDS), "el adb logcat se cerró")
        assertFalse(raws.single().exists())
        assertEquals(emptyList<String>(), records())
    }

    @Test
    fun `una corrida que murio sin limpiar no deja el buffer en 16M como tamano original de la siguiente`() {
        val logd = FakeLogd(original)
        val first = capture(logd)
        first.start(tmp)
        Cleanup.unregister(first) // kill -9: ningún shutdown hook corre
        val record = store.entries().single().second!!
        store.delete(store.file(ProcessHandle.current().pid()))
        store.write(store.file(2147483000), record.copy(ownerPid = 2147483000)) // el dueño ya no existe
        assertEquals(setOf("16M"), logd.sizes.values.toSet())

        val second = capture(logd)
        second.start(tmp)
        second.finish(tmp, Redactor(RedactRules.NONE))

        assertEquals(original, logd.sizes, "la segunda corrida devolvió los 256 KiB de verdad, no los 16M que encontró")
        assertEquals(emptyList<String>(), records())
    }

    @Test
    fun `cierra una captura vieja cuya corrida ya no existe`() {
        val stale = fakeLogcat()
        store.write(store.file(2147483000), CaptureRecord(ownerPid = 2147483000, logcatPid = stale.pid()))
        val capture = capture(FakeLogd(original))

        capture.start(tmp)
        val outcome = capture.finish(tmp, Redactor(RedactRules.NONE))

        assertTrue(stale.waitFor(5, TimeUnit.SECONDS), "la captura vieja se cerró")
        assertTrue(outcome.note!!.contains("captura de logcat de una corrida anterior"), outcome.note)
        assertEquals(emptyList<String>(), records(), "al terminar no queda nada")
    }

    @Test
    fun `no toca la captura de otra corrida que sigue viva ni un proceso que no es logcat`() {
        val live = fakeLogcat()
        val unrelated = sleeper()
        val noop: (Map<String, String>) -> Boolean = { true }

        val liveFile = store.file(ProcessHandle.current().pid())
        store.write(liveFile, CaptureRecord(ownerPid = ProcessHandle.current().pid(), logcatPid = live.pid())) // el dueño (esta JVM) está vivo
        assertEquals(emptyList<String>(), StaleCapture.close(store, noop))
        assertTrue(live.isAlive, "es de una corrida en marcha")
        assertTrue(liveFile.exists(), "y su anotación sigue ahí")

        store.delete(liveFile)
        val deadFile = store.file(2147483000)
        store.write(deadFile, CaptureRecord(ownerPid = 2147483000, logcatPid = unrelated.pid())) // dueño muerto, pero el pid ya no es de un logcat
        assertEquals(emptyList<String>(), StaleCapture.close(store, noop))
        assertTrue(unrelated.isAlive, "no mata procesos que no son adb logcat")
        assertFalse(deadFile.exists(), "la anotación vieja sí se borra")

        assertEquals(emptyList<String>(), StaleCapture.close(CaptureStore(tmp.resolve("no-existe")), noop))
    }

    @Test
    fun `dos corridas sobre el mismo dispositivo no se pisan, y la segunda no devuelve los buffers mientras la primera sigue`() {
        val logd = FakeLogd(original)
        val ownerA = sleeper() // otra corrida de testigo, viva
        val logcatA = fakeLogcat()
        val fileA = store.file(ownerA.pid())
        store.write(fileA, CaptureRecord(ownerPid = ownerA.pid(), logcatPid = logcatA.pid(), buffers = original))
        logd.enlarged() // A ya agrandó los buffers

        val b = capture(logd)
        b.start(tmp)
        assertTrue(fileA.exists(), "B no borra la anotación de A al empezar")
        assertTrue(logcatA.isAlive, "ni cierra su logcat")
        assertFalse(logd.commands.any { "-g" in it.drop(3) }, "lo que dice logcat -g ya no es lo original: B toma lo que anotó A")
        b.finish(tmp, Redactor(RedactRules.NONE))

        assertTrue(fileA.exists(), "B no borra la anotación de A al terminar")
        assertTrue(logcatA.isAlive)
        assertEquals(setOf("16M"), logd.sizes.values.toSet(), "A sigue necesitando los buffers agrandados")
        assertEquals(listOf(fileA.fileName.toString()), records(), "B solo borró la suya")
    }

    @Test
    fun `la ultima corrida en salir devuelve los buffers, con el tamano original que anoto la primera`() {
        val logd = FakeLogd(original)
        val ownerA = sleeper()
        store.write(store.file(ownerA.pid()), CaptureRecord(ownerPid = ownerA.pid(), logcatPid = fakeLogcat().pid(), buffers = original))
        logd.enlarged()
        val b = capture(logd)
        b.start(tmp)

        ownerA.destroyForcibly() // A termina antes que B
        ownerA.waitFor()
        b.finish(tmp, Redactor(RedactRules.NONE))

        assertEquals(original, logd.sizes, "B era la última: devuelve los 256 KiB, no los 16M que veía")
    }

    @Test
    fun `si adb no contesta al devolver los buffers lo avisa, lo deja anotado y la siguiente lo devuelve`() {
        val logd = FakeLogd(original)
        val first = capture(logd)
        first.start(tmp)

        logd.unreachableOnSet = true
        val outcome = first.finish(tmp, Redactor(RedactRules.NONE))
        assertTrue(outcome.note!!.contains("no pude devolver los buffers"), outcome.note)
        assertEquals(1, records().size, "queda anotado cómo estaban")
        assertEquals(null, store.entries().single().second!!.logcatPid, "pero ya no apunta a un adb logcat que terminó: el pid podría reusarse")
        logd.unreachableOnSet = false

        val second = capture(logd)
        second.start(tmp)
        second.finish(tmp, Redactor(RedactRules.NONE))

        assertEquals(original, logd.sizes, "la siguiente no toma los 16M por originales")
        assertEquals(emptyList<String>(), records())
    }

    @Test
    fun `si falla el arranque de la captura tambien devuelve los buffers`() {
        val logd = object : CommandRunner {
            val inner = FakeLogd(original)
            override fun run(command: List<String>, timeout: Duration): CommandResult {
                if ("-c" in command.drop(3)) throw TestigoException("adb no terminó en 30s.")
                return inner.run(command, timeout)
            }
        }
        val capture = capture(logd)

        assertThrows(TestigoException::class.java) { capture.start(tmp) }

        assertEquals(original, logd.inner.sizes, "logcat -c falló después de agrandar los buffers")
        assertEquals(emptyList<String>(), records())
    }
}
