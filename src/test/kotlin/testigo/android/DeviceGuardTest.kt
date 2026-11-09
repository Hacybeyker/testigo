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

        /** Si no es null, lo que `dumpsys power` dice de `mWakefulness` en lugar de Awake o Asleep. */
        var wakefulness: String? = null

        /** Si no es null, el volcado de `dumpsys window policy` que contesta, en lugar de un `showing=` suelto. */
        var policyDump: String? = null

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
                    ok(wakefulness ?: if (asleep) "  mWakefulness=Asleep\nmWakefulness=0\n" else "  mWakefulness=Awake\nmWakefulness=1\n")
                shell.startsWith("dumpsys window policy") -> ok(policyDump ?: "      showing=$keyguard\n")
                shell == "input keyevent KEYCODE_WAKEUP" -> {
                    asleep = false
                    keyguard = true // al despertar, Android muestra el keyguard
                    ok("")
                }
                shell == "wm dismiss-keyguard" -> {
                    if (!secure) {
                        keyguard = false
                        policyDump = policyDump?.replace("showing=true", "showing=false")
                    }
                    ok("")
                }
                else -> ok("")
            }
        }

        private fun ok(stdout: String) = CommandResult(0, stdout, "")
    }

    private var clock = 0L

    private fun guard(device: FakeDevice, readyTimeout: Duration = 3.minutes, sdk: Int? = null) = AdbDeviceGuard(
        adb = Adb(Path.of("adb"), device),
        serial = "emulator-5554",
        packageName = "com.empresa.app",
        sdk = sdk,
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

    @Test
    fun `en Android 6 o anterior no existe resolve-activity y no hace esperar 15 s en cada flow`() {
        val device = FakeDevice().apply { resolves = false } // un comando que no existe nunca contesta con una Activity

        val old = guard(device, sdk = 23).beforeFlow()

        assertNull(old.problem)
        assertEquals(0L, clock, "con API 23 solo se mira que el Package Manager vea la app")
        assertFalse(device.shells.any { it.startsWith("cmd package resolve-activity") }, device.shells.toString())

        clock = 0
        guard(device, sdk = 24).beforeFlow()
        assertTrue(clock >= 15_000L, "desde API 24 sí lo pregunta, y espera el margen: $clock")
    }

    @Test
    fun `con el salvapantallas puesto manda una tecla, porque tapa la app`() {
        val device = FakeDevice().apply { wakefulness = "  mWakefulness=Dreaming\nmWakefulness=2\n" }

        val check = guard(device).beforeFlow()

        assertTrue("input keyevent KEYCODE_WAKEUP" in device.shells, device.shells.toString())
        assertTrue(check.notes.any { "pantalla estaba apagada" in it }, check.notes.toString())
    }

    @Test
    fun `un reinicio que se avisa como ocurrido durante un flow no se avisa otra vez como previo al siguiente`() {
        val device = FakeDevice()
        val guard = guard(device)
        guard.beforeFlow()

        device.bootReadable = false
        assertTrue(guard.restartedDuringFlow(), "adb no contesta al terminar: se reinicia")
        device.bootReadable = true
        device.boot = "boot-2"
        val next = guard.beforeFlow()

        assertFalse(next.restarted, "el reinicio ya se contó en el flow anterior")
        device.boot = "boot-3"
        assertTrue(guard.beforeFlow().restarted, "pero el nuevo boot id sí quedó como referencia")
    }

    @Test
    fun `lee la salida real de Android 17 sin bloqueo, sin tocar nada`() {
        val device = FakeDevice().apply { policyDump = ANDROID_17_UNLOCKED }

        val check = guard(device).beforeFlow()

        assertNull(check.problem)
        assertFalse(device.shells.any { it.startsWith("input ") || it.startsWith("wm ") }, device.shells.toString())
    }

    @Test
    fun `con la salida real de Android 17 con PIN, que ya no trae secure, intenta quitarlo y dice que no se puede`() {
        val device = FakeDevice().apply {
            policyDump = ANDROID_17_PIN_LOCKED
            secure = true
        }

        val problem = guard(device).beforeFlow().problem

        assertTrue("wm dismiss-keyguard" in device.shells, "sin secure= en el volcado no puede saber que es de PIN: lo intenta")
        assertTrue("PIN, patrón o contraseña" in problem!!, problem)
    }

    @Test
    fun `si el volcado dice secure true no intenta quitar el keyguard`() {
        val device = FakeDevice().apply { policyDump = OLDER_SECURE }

        val problem = guard(device).beforeFlow().problem

        assertFalse("wm dismiss-keyguard" in device.shells, "un keyguard seguro no se quita con dismiss-keyguard: ${device.shells}")
        assertEquals(0L, clock, "y no pierde un segundo esperando")
        assertTrue("PIN, patrón o contraseña" in problem!! && "locksettings clear" in problem, problem)
    }

    @Test
    fun `con secure false, un keyguard de deslizar, sigue quitandolo`() {
        val device = FakeDevice().apply {
            policyDump = OLDER_SECURE.replace("secure=true", "secure=false")
            secure = false
            keyguard = true
        }

        val check = guard(device).beforeFlow()

        assertNull(check.problem)
        assertTrue("wm dismiss-keyguard" in device.shells)
    }

    private companion object {
        /**
         * `adb shell dumpsys window policy` capturado de un emulador con Android 17 (API 37.0), recortado al bloque
         * KeyguardServiceDelegate. Pantalla encendida y sin bloqueo. Esta versión ya no imprime `secure=`, solo `simSecure=`.
         */
        val ANDROID_17_UNLOCKED = """
            KeyguardServiceDelegate
              showing=false
              inputRestricted=false
              occluded=false
              trusted=false
              simSecure=false
              dreaming=false
              systemReady=true
              deviceHasKeyguard=true
              enabled=true
              userId=0
              bootCompleted=true
              screenState=SCREEN_STATE_ON
              interactiveState=INTERACTIVE_STATE_AWAKE
        """.trimIndent()

        /** Lo mismo, capturado con un PIN puesto y la pantalla recién despertada de un `KEYCODE_SLEEP`. */
        val ANDROID_17_PIN_LOCKED = ANDROID_17_UNLOCKED.replace("showing=false", "showing=true").replace("inputRestricted=false", "inputRestricted=true")

        /**
         * El formato con `secure=` de versiones anteriores de Android. NO está capturado de un dispositivo: es la forma que
         * tiene `KeyguardServiceDelegate` en el código de AOSP, reducida a las líneas que lee el guardián.
         */
        val OLDER_SECURE = """
            KeyguardServiceDelegate
              showing=true
              showingAndNotOccluded=true
              inputRestricted=true
              occluded=false
              secure=true
              dreaming=false
        """.trimIndent()
    }
}
