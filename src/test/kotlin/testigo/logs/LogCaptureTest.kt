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
            return CommandResult(0, if ("crash" in command) crash else "", "")
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

    /** adb simulado que contesta `logcat -g` como un emulador con 256 KiB y deja anotado lo demás. */
    private class SizedAdb(private val setWorks: Boolean = true) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return when {
                command.contains("-g") -> CommandResult(
                    0,
                    "main: ring buffer is 256 KiB (13 KiB consumed), max entry is 5120 B, max payload is 4068 B\n" +
                        "system: ring buffer is 256 KiB (1 KiB consumed), max entry is 5120 B, max payload is 4068 B\n",
                    "",
                )
                command.contains("-G") -> if (setWorks) CommandResult(0, "", "") else CommandResult(1, "", "failed to set the buffer size")
                else -> CommandResult(0, "", "")
            }
        }
    }

    private fun capture(runner: CommandRunner, pidFile: Path? = null) = LogCapture(
        adb = Path.of("/sdk/platform-tools/adb"),
        serial = "NJ4",
        packageName = "com.empresa.app",
        uid = 10234,
        sdk = 33,
        runner = runner,
        pidFile = pidFile,
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

        val sizes = adb.commands.filter { "-G" in it }.map { it.last() }
        assertEquals(listOf("16M", "256K"), sizes, "sube a 16M y vuelve a los 256 KiB que tenía")
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
    fun `lee el tamano del buffer de logcat -g`() {
        assertEquals("256K", LogBuffer.parseSize("main: ring buffer is 256 KiB (13 KiB consumed)"))
        assertEquals("4M", LogBuffer.parseSize("main: ring buffer is 4 MiB (1 MiB consumed)"))
        assertEquals("256K", LogBuffer.parseSize("main: ring buffer is 256Kb (12Kb consumed)"))
        assertNull(LogBuffer.parseSize("no es la salida de logcat"))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `cierra una captura vieja cuya corrida ya no existe`() {
        // Un proceso que se hace pasar por un adb logcat huérfano.
        val stale = ProcessBuilder("sh", "-c", "sleep 60; echo adb logcat").start()
        try {
            val pidFile = tmp.resolve("run/logcat-NJ4.pid")
            pidFile.parent.toFile().mkdirs()
            pidFile.writeText("${stale.pid()} 2147483000") // el dueño no existe
            val capture = capture(SizedAdb(), pidFile)

            capture.start(tmp)
            val outcome = capture.finish(tmp, Redactor(RedactRules.NONE))

            assertTrue(stale.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "la captura vieja se cerró")
            assertTrue(outcome.note!!.contains("captura de logcat de una corrida anterior"), outcome.note)
            assertFalse(pidFile.exists(), "al terminar no queda el pid")
        } finally {
            stale.destroyForcibly()
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `no toca la captura de otra corrida que sigue viva ni un proceso que no es logcat`() {
        val live = ProcessBuilder("sh", "-c", "sleep 60; echo adb logcat").start()
        val unrelated = ProcessBuilder("sleep", "60").start()
        try {
            val pidFile = tmp.resolve("logcat-NJ4.pid")

            pidFile.writeText("${live.pid()} ${ProcessHandle.current().pid()}") // el dueño (esta JVM) está vivo
            assertNull(StaleCapture.close(pidFile))
            assertTrue(live.isAlive, "es de una corrida en marcha")

            pidFile.writeText("${unrelated.pid()} 2147483000") // dueño muerto, pero el pid ya no es de un logcat
            assertNull(StaleCapture.close(pidFile))
            assertTrue(unrelated.isAlive, "no mata procesos que no son adb logcat")

            assertNull(StaleCapture.close(tmp.resolve("no-existe.pid")))
        } finally {
            live.destroyForcibly()
            unrelated.destroyForcibly()
        }
    }
}
