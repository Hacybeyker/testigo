package testigo.logs

import testigo.process.CommandResult
import testigo.process.CommandRunner
import testigo.redact.RedactRules
import testigo.redact.Redactor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration

class LogCaptureTest {

    @TempDir
    lateinit var tmp: Path

    /** Buffer de crashes con un crash de otra app y uno de la nuestra. */
    private val crashBuffer = """
        --------- beginning of crash
        10-16 15:10:01.100  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main
        10-16 15:10:01.100  4321  4321 E AndroidRuntime: Process: com.otra.app, PID: 4321
        10-16 15:10:01.100  4321  4321 E AndroidRuntime: java.lang.NullPointerException: otra app
        10-16 15:10:05.200  5555  5555 E AndroidRuntime: FATAL EXCEPTION: main
        10-16 15:10:05.200  5555  5555 E AndroidRuntime: Process: com.empresa.app, PID: 5555
        10-16 15:10:05.200  5555  5555 E AndroidRuntime: android.app.RemoteServiceException${'$'}CrashedByAdbException: shell-induced crash
        10-16 15:10:05.200  5555  5555 E AndroidRuntime: 	at android.app.ActivityThread.throwRemoteServiceException(ActivityThread.java:2111)
    """.trimIndent()

    @Test
    fun `del buffer de crashes toma solo el crash de la app`() {
        val crash = CrashLog.extract(crashBuffer, "com.empresa.app")!!

        assertEquals(4, crash.lines().size, crash)
        assertFalse(crash.contains("com.otra.app"), crash)
        assertEquals(
            "android.app.RemoteServiceException\$CrashedByAdbException: shell-induced crash",
            CrashLog.summary(crash),
        )
        assertNull(CrashLog.extract(crashBuffer, "com.sin.crash"))
    }

    @Test
    fun `reconoce un crash nativo`() {
        val buffer = """
            10-16 15:20:00.000  7777  7777 F DEBUG   : pid: 6666, tid: 6666, name: empresa.app  >>> com.empresa.app <<<
            10-16 15:20:00.000  7777  7777 F DEBUG   : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
        """.trimIndent()

        assertEquals("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0", CrashLog.summary(CrashLog.extract(buffer, "com.empresa.app")!!))
    }

    /** adb simulado: devuelve [crash] para `logcat -b crash` y anota cada comando. */
    private class FakeAdb(private val crash: String) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return CommandResult(0, if ("-g" in command) EMULATOR_SIZES else if ("-d" in command && "crash" in command) crash else "", "")
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `logcat filtrado por uid, enmascarado y con crash txt si la app crasheo`() {
        val adb = FakeAdb(crashBuffer)
        val launched = mutableListOf<List<String>>()
        val capture = LogCapture(
            adb = Path.of("/sdk/platform-tools/adb"),
            serial = "NJ4",
            packageName = "com.empresa.app",
            uid = 10234,
            sdk = 33,
            runner = adb,
            startProcess = { command, output ->
                launched += command
                output.writeText(
                    "10-16 15:10:00.000  5555  5555 I MiApp: login con Secreto123\n" +
                        "10-16 15:10:00.100  5555  5555 D OkHttp : Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345\n",
                )
                ProcessBuilder("sleep", "30").start()
            },
        )

        capture.start(tmp)
        val outcome = capture.finish(tmp, Redactor(RedactRules.DEFAULT, mapOf("PASSWORD" to "Secreto123")))

        val logcat = adb.commands.map { it.drop(3) }.filter { it.firstOrNull() == "logcat" }
        assertEquals(listOf("logcat", "-g"), logcat[0], "primero lee el tamaño del buffer para devolverlo")
        assertEquals(listOf("logcat", "-G", "16M"), logcat[1])
        assertEquals(listOf("logcat", "-c"), logcat[2], "limpia después de agrandar el buffer")
        assertTrue("--uid=10234" in launched.single(), launched.toString())
        assertEquals(tmp.resolve("logcat.txt"), outcome.logcat)
        val text = outcome.logcat!!.readText()
        assertTrue(text.contains("login con ****"), text)
        assertTrue(text.contains("Authorization: ***"), text)
        assertEquals(listOf("crash.txt", "logcat.txt"), tmp.toFile().list()!!.sorted(), "el crudo no queda en la carpeta")
        assertEquals(tmp.resolve("crash.txt"), outcome.crashFile)
        assertTrue(outcome.crash!!.contains("CrashedByAdbException"), outcome.crash)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `sin crash no hay crash txt, y en Android 9 no se filtra por uid`() {
        val launched = mutableListOf<List<String>>()
        val capture = LogCapture(
            adb = Path.of("/sdk/platform-tools/adb"),
            serial = "NJ4",
            packageName = "com.empresa.app",
            uid = 10234,
            sdk = 28,
            runner = FakeAdb(""),
            startProcess = { command, output ->
                launched += command
                output.writeText("")
                ProcessBuilder("sleep", "30").start()
            },
        )

        capture.start(tmp)
        val outcome = capture.finish(tmp, Redactor(RedactRules.NONE))

        assertFalse(launched.single().any { it.startsWith("--uid") }, launched.toString())
        assertNull(outcome.crash)
        assertFalse(tmp.resolve("crash.txt").exists())
    }

    /** adb simulado que contesta `logcat -g` como un emulador con 256 KiB (o con [sizes], o con un fallo) y anota lo demás. */
    private class SizedAdb(private val setWorks: Boolean = true, private val sizes: String? = EMULATOR_SIZES) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return when {
                command.contains("-g") -> if (sizes != null) CommandResult(0, sizes, "") else CommandResult(1, "", "logcat: permission denied")
                command.contains("-G") -> if (setWorks) CommandResult(0, "", "") else CommandResult(1, "", "failed to set the buffer size")
                else -> CommandResult(0, "", "")
            }
        }

        /** Los argumentos de logcat de cada comando, sin `adb -s <serial>`. */
        val logcat: List<List<String>> get() = commands.map { it.drop(3) }.filter { it.firstOrNull() == "logcat" }
    }

    private fun capture(runner: CommandRunner) = LogCapture(
        adb = Path.of("/sdk/platform-tools/adb"),
        serial = "NJ4",
        packageName = "com.empresa.app",
        uid = 10234,
        sdk = 33,
        runner = runner,
        startProcess = { _, output ->
            output.writeText("")
            ProcessBuilder("sleep", "30").start()
        },
    )

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `agranda el buffer de logcat y al terminar lo deja como estaba`() {
        val adb = SizedAdb()
        val capture = capture(adb)

        capture.start(tmp)
        capture.finish(tmp, Redactor(RedactRules.NONE))

        val sizes = adb.logcat.filter { "-G" in it }
        assertEquals(
            listOf(listOf("logcat", "-G", "16M"), listOf("logcat", "-b", "main,system", "-G", "256K")),
            sizes,
            "sube a 16M y vuelve a los 256 KiB que tenía",
        )
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `si el dispositivo no deja cambiar el buffer lo avisa y sigue`() {
        val adb = SizedAdb(setWorks = false)
        val capture = capture(adb)

        capture.start(tmp)
        val outcome = capture.finish(tmp, Redactor(RedactRules.NONE))

        assertTrue(outcome.note!!.contains("no dejó agrandar el buffer"), outcome.note)
        assertEquals(1, adb.commands.count { "-G" in it }, "no intenta restaurar lo que no cambió")
        assertTrue(outcome.logcat!!.exists(), "la captura sigue funcionando")
    }

    @Test
    fun `lee el tamano de cada buffer de logcat -g`() {
        assertEquals(mapOf("main" to "256K", "system" to "256K"), LogBuffer.parseSizes(EMULATOR_SIZES))
        assertEquals(mapOf("main" to "4M"), LogBuffer.parseSizes("main: ring buffer is 4 MiB (1 MiB consumed)"))
        assertEquals(mapOf("main" to "256K"), LogBuffer.parseSizes("main: ring buffer is 256Kb (12Kb consumed)"), "Android 9 y anteriores")
        assertEquals(mapOf("main" to "1536K", "crash" to "1G"), LogBuffer.parseSizes("main: ring buffer is 1.5 MiB (1 MiB consumed)\ncrash: ring buffer is 1 GiB (0 B consumed)"))
        assertTrue(LogBuffer.parseSizes("no es la salida de logcat").isEmpty())
    }

    /** Lo que imprime `logcat -g` en Android 17 (API 37), capturado de un emulador: cuatro buffers, no solo `main`. */
    @Test
    fun `lee la salida real de logcat -g de un emulador con cuatro buffers`() {
        val real = """
            main: ring buffer is 2 MiB (1 MiB consumed, 6 MiB readable), max entry is 5120 B, max payload is 4068 B
            system: ring buffer is 2 MiB (743 KiB consumed, 1 MiB readable), max entry is 5120 B, max payload is 4068 B
            crash: ring buffer is 2 MiB (0 B consumed, 0 B readable), max entry is 5120 B, max payload is 4068 B
            kernel: ring buffer is 2 MiB (0 B consumed, 0 B readable), max entry is 5120 B, max payload is 4068 B
        """.trimIndent()

        assertEquals(mapOf("main" to "2M", "system" to "2M", "crash" to "2M", "kernel" to "2M"), LogBuffer.parseSizes(real))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `cada buffer vuelve a su propio tamano, no al de main`() {
        val adb = SizedAdb(
            sizes = "main: ring buffer is 256 KiB (13 KiB consumed)\nsystem: ring buffer is 256 KiB (1 KiB consumed)\n" +
                "crash: ring buffer is 64 KiB (0 B consumed)\n",
        )
        val capture = capture(adb)

        capture.start(tmp)
        capture.finish(tmp, Redactor(RedactRules.NONE))

        val restored = adb.logcat.filter { "-G" in it && "16M" !in it }
        assertEquals(
            listOf(listOf("logcat", "-b", "main,system", "-G", "256K"), listOf("logcat", "-b", "crash", "-G", "64K")),
            restored,
            "el buffer de crashes de 64 KiB no se sube a 256 KiB",
        )
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `lee el buffer de crashes antes de devolver el tamano, que poda lo mas viejo`() {
        val adb = SizedAdb()
        val capture = capture(adb)

        capture.start(tmp)
        capture.finish(tmp, Redactor(RedactRules.NONE))

        val crashRead = adb.logcat.indexOfFirst { "crash" in it && "-d" in it }
        val restore = adb.logcat.indexOfLast { "-G" in it }
        assertTrue(crashRead in 0 until restore, "primero leer los crashes ($crashRead), luego achicar ($restore): ${adb.logcat}")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `si no puede leer el tamano del buffer no lo agranda, porque no podria devolverlo, y lo avisa`() {
        val adb = SizedAdb(sizes = null)
        val capture = capture(adb)

        capture.start(tmp)
        val outcome = capture.finish(tmp, Redactor(RedactRules.NONE))

        assertEquals(0, adb.commands.count { "-G" in it }, "sin saber cómo estaba, el buffer no se toca: logcat -G persiste")
        assertTrue(outcome.note!!.contains("no pude leer el tamaño del buffer"), outcome.note)
        assertTrue(outcome.logcat!!.exists(), "la captura sigue funcionando")
    }

    private companion object {
        const val EMULATOR_SIZES = "main: ring buffer is 256 KiB (13 KiB consumed), max entry is 5120 B, max payload is 4068 B\n" +
            "system: ring buffer is 256 KiB (1 KiB consumed), max entry is 5120 B, max payload is 4068 B\n"
    }
}
