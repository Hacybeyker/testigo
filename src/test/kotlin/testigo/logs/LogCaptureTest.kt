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

        assertEquals(listOf("/sdk/platform-tools/adb", "-s", "NJ4", "logcat", "-c"), adb.commands.first())
        assertTrue("--uid=10234" in launched.single(), launched.toString())
        assertEquals(tmp.resolve("logcat.txt"), outcome.logcat)
        val logcat = outcome.logcat!!.readText()
        assertTrue(logcat.contains("login con ****"), logcat)
        assertTrue(logcat.contains("Authorization: ***"), logcat)
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
}
