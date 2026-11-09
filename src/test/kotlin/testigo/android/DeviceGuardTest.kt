package testigo.android

import testigo.process.CommandResult
import testigo.process.CommandRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DeviceGuardTest {

    /** Un dispositivo simulado: contesta como un Android real a lo que el guardián le pregunta por `adb shell`. */
    private class FakeDevice : CommandRunner {
        var boot = "boot-1"
        var bootReadable = true

        /** Cuántas veces `sys.boot_completed` todavía contesta vacío. */
        var bootingPolls = 0
        var installed = true
        var resolves = true
        var asleep = false
        var keyguard = false

        /** Un bloqueo con PIN: `wm dismiss-keyguard` no lo quita. */
        var secure = false
        val shells = mutableListOf<String>()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            val shell = command.last()
            shells += shell
            return when {
                shell.startsWith("cat /proc/sys/kernel/random/boot_id") ->
                    if (bootReadable) ok("$boot\n") else CommandResult(1, "", "error: device offline")
                shell.startsWith("getprop sys.boot_completed") -> ok(if (bootingPolls-- > 0) "\n" else "1\n")
                shell.startsWith("pm path") -> if (installed) ok("package:/data/app/base.apk\n") else CommandResult(1, "", "")
                shell.startsWith("cmd package resolve-activity") ->
                    ok(if (resolves) "priority=0 preferredOrder=0 match=0x108000\ncom.empresa.app/.MainActivity\n" else "No activity found\n")
                shell.startsWith("dumpsys power") ->
                    ok(if (asleep) "  mWakefulness=Asleep\nmWakefulness=0\n" else "  mWakefulness=Awake\nmWakefulness=1\n")
                shell.startsWith("dumpsys window policy") -> ok("      showing=$keyguard\n")
                shell == "input keyevent KEYCODE_WAKEUP" -> {
                    asleep = false
                    keyguard = true // al despertar, Android muestra el keyguard
                    ok("")
                }
                shell == "wm dismiss-keyguard" -> {
                    if (!secure) keyguard = false
                    ok("")
                }
                else -> ok("")
            }
        }

        private fun ok(stdout: String) = CommandResult(0, stdout, "")
    }

    private var clock = 0L

    private fun guard(device: FakeDevice, readyTimeout: Duration = 3.minutes) = AdbDeviceGuard(
        adb = Adb(Path.of("adb"), device),
        serial = "emulator-5554",
        packageName = "com.empresa.app",
        readyTimeout = readyTimeout,
        sleep = { clock += it.inWholeMilliseconds },
        now = { clock },
    )

    @Test
    fun `un dispositivo listo no necesita nada`() {
        val check = guard(FakeDevice()).beforeFlow()

        assertNull(check.problem)
        assertFalse(check.restarted)
        assertTrue(check.notes.isEmpty(), check.notes.toString())
        assertEquals(0L, clock, "no espera si ya está listo")
    }

    @Test
    fun `espera a que Android termine de arrancar`() {
        val device = FakeDevice().apply { bootingPolls = 3 }

        val check = guard(device).beforeFlow()

        assertNull(check.problem)
        assertEquals(6_000L, clock, "tres sondeos de 2 s antes de que sys.boot_completed diga 1")
    }

    @Test
    fun `si no esta listo a tiempo, dice por que y que no es de la app`() {
        val device = FakeDevice().apply { bootingPolls = Int.MAX_VALUE }

        val check = guard(device, readyTimeout = 1.minutes).beforeFlow()

        val problem = check.problem!!
        assertTrue("no estuvo listo en 1m" in problem && "todavía está arrancando" in problem, problem)
        assertTrue("No es un defecto de la app" in problem, problem)
    }

    @Test
    fun `una app que no esta instalada lo dice`() {
        val device = FakeDevice().apply { installed = false }

        val problem = guard(device, readyTimeout = 30.seconds).beforeFlow().problem!!

        assertTrue("¿se desinstaló?" in problem, problem)
    }

    @Test
    fun `una app sin Activity no bloquea para siempre, solo espera un margen`() {
        val device = FakeDevice().apply { resolves = false }

        val check = guard(device).beforeFlow()

        assertNull(check.problem)
        assertTrue(clock >= 15_000L, "insiste 15 s antes de aceptar que no hay Activity: $clock")
    }

    @Test
    fun `un cambio de boot id entre flows es un reinicio`() {
        val device = FakeDevice()
        val guard = guard(device)

        assertFalse(guard.beforeFlow().restarted, "la primera revisión fija la referencia")
        device.boot = "boot-2"
        assertTrue(guard.beforeFlow().restarted)
        assertFalse(guard.beforeFlow().restarted, "ya se avisó una vez")
    }

    @Test
    fun `detecta un reinicio durante el flow, o un dispositivo que deja de responder`() {
        val device = FakeDevice()
        val guard = guard(device)
        guard.beforeFlow()

        assertFalse(guard.restartedDuringFlow())
        device.boot = "boot-2"
        assertTrue(guard.restartedDuringFlow())
        assertFalse(guard.restartedDuringFlow(), "el nuevo boot id ya es la referencia")

        device.bootReadable = false
        assertTrue(guard.restartedDuringFlow(), "sin respuesta de adb al terminar: casi seguro se reinicia")
    }

    @Test
    fun `enciende la pantalla apagada y descarta el keyguard de deslizar`() {
        val device = FakeDevice().apply { asleep = true }

        val check = guard(device).beforeFlow()

        assertNull(check.problem)
        assertTrue(check.notes.any { "pantalla estaba apagada" in it }, check.notes.toString())
        assertTrue(check.notes.any { "bloqueo de pantalla" in it }, check.notes.toString())
        assertTrue("input keyevent KEYCODE_WAKEUP" in device.shells)
        assertTrue("wm dismiss-keyguard" in device.shells)
    }

    @Test
    fun `un bloqueo con PIN no se puede saltar, lo dice y detiene la corrida`() {
        val device = FakeDevice().apply {
            asleep = true
            secure = true
        }

        val problem = guard(device).beforeFlow().problem

        assertNotNull(problem)
        assertTrue("PIN, patrón o contraseña" in problem!! && "locksettings clear" in problem, problem)
    }

    @Test
    fun `con la pantalla ya encendida y sin keyguard no manda ninguna tecla`() {
        val device = FakeDevice()

        guard(device).beforeFlow()

        assertFalse(device.shells.any { it.startsWith("input ") || it.startsWith("wm ") }, device.shells.toString())
    }
}
