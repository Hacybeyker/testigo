package testigo.logs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Cuándo un proceso vivo es el `adb logcat` que lanzó una corrida y cuándo solo tiene su mismo pid. */
class ProcessIdentityTest {

    private val start = 1_760_000_000_000L

    private fun logcat(vararg args: String, at: Long? = start) = ProcessFacts(at, "/sdk/platform-tools/adb", args.toList())

    @Test
    fun `es el adb logcat de este dispositivo que arranco a la hora anotada`() {
        assertTrue(Identity.isLogcat(logcat("-s", "NJ4", "logcat", "-v", "threadtime", "--uid=10234"), start, "NJ4"))
        assertTrue(Identity.isLogcat(logcat("-s", "NJ4", "logcat"), start + 1_500, "NJ4"), "Linux mide el arranque con un segundo de diferencia")
    }

    @Test
    fun `un pid reusado arranco despues y no es el mismo proceso`() {
        assertFalse(Identity.isLogcat(logcat("-s", "NJ4", "logcat", at = start + 60_000), start, "NJ4"))
        assertFalse(Identity.isLogcat(logcat("-s", "NJ4", "logcat", at = start - 5_000), start, "NJ4"))
    }

    @Test
    fun `sin saber cuando arranco, ni anotado ni ahora, no se cierra`() {
        assertFalse(Identity.isLogcat(logcat("-s", "NJ4", "logcat"), null, "NJ4"))
        assertFalse(Identity.isLogcat(logcat("-s", "NJ4", "logcat", at = null), start, "NJ4"))
    }

    @Test
    fun `exige logcat y -s con el serial en los argumentos`() {
        assertFalse(Identity.isLogcat(logcat("-s", "OTRO", "logcat"), start, "NJ4"), "logcat de otro dispositivo")
        assertFalse(Identity.isLogcat(logcat("logcat", "-v", "threadtime"), start, "NJ4"), "logcat sin -s")
        assertFalse(Identity.isLogcat(logcat("-s", "NJ4", "shell", "ls"), start, "NJ4"), "adb de este dispositivo, pero no logcat")
        assertFalse(Identity.isLogcat(ProcessFacts(start, "/usr/bin/tail", listOf("-f", "/tmp/logcat.txt")), start, "NJ4"), "tail -f logcat.txt")
        assertFalse(Identity.isLogcat(logcat("NJ4", "-s", "logcat"), start, "NJ4"), "el serial debe ir justo después de -s")
    }

    @Test
    fun `donde el sistema no dice los argumentos, como Windows, basta el adb a la hora anotada`() {
        val noArgs = ProcessFacts(start, "C:\\Android\\platform-tools\\adb.exe", null)

        assertTrue(Identity.isLogcat(noArgs, start, "NJ4"))
        assertFalse(Identity.isLogcat(noArgs.copy(startMillis = start + 60_000), start, "NJ4"), "el servidor de adb, que arrancó antes o después, no")
        assertFalse(Identity.isLogcat(noArgs.copy(executable = "C:\\Windows\\notepad.exe"), start, "NJ4"))
        assertFalse(Identity.isLogcat(noArgs.copy(executable = null), start, "NJ4"))
    }
}
