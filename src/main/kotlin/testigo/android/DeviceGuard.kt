package testigo.android

import testigo.TestigoException
import testigo.process.CommandResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Lo que testigo encontró en el dispositivo antes de un flow. */
data class DeviceCheck(
    /** null si el dispositivo está listo. Si no, por qué no se puede correr el flow: es del entorno, no de la app. */
    val problem: String? = null,
    /** El dispositivo se reinició desde la última revisión. */
    val restarted: Boolean = false,
    /** Lo que testigo hizo para dejarlo listo, como encender la pantalla. */
    val notes: List<String> = emptyList(),
)

/**
 * Cuida que el dispositivo esté en condiciones antes y después de cada flow, para que un fallo del entorno no se confunda con
 * un defecto de la app. Una interfaz para poder probar el ejecutor sin dispositivo.
 */
interface DeviceGuard {
    /** Espera a que el dispositivo esté listo y con la pantalla encendida y desbloqueada. */
    fun beforeFlow(): DeviceCheck

    /** Después del flow: true si el dispositivo se reinició, o dejó de responder, mientras corría. */
    fun restartedDuringFlow(): Boolean
}

/**
 * [DeviceGuard] con adb.
 * - Listo: `sys.boot_completed` en 1 y la app resuelta por el Package Manager. Tras un reinicio, `adb shell` falla un rato
 *   (`Can't find service: package`) y `am start` dice que la Activity no existe: sin esta espera, el primer flow tras el
 *   reinicio falla por culpa del entorno.
 * - Reinicio: compara el `boot_id` del kernel entre flows. Cambia solo si el dispositivo se reinició.
 * - Pantalla: la enciende si está apagada y descarta el keyguard de deslizar. Un bloqueo con PIN, patrón o contraseña
 *   no se puede saltar: se dice y se detiene la corrida.
 */
class AdbDeviceGuard(
    private val adb: Adb,
    private val serial: String,
    private val packageName: String,
    private val readyTimeout: Duration = 3.minutes,
    private val pollInterval: Duration = 2.seconds,
    /** Cuánto se insiste en que Android resuelva una Activity de la app antes de aceptar que no tiene ninguna. */
    private val activityGrace: Duration = 15.seconds,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.inWholeMilliseconds) },
    private val now: () -> Long = System::currentTimeMillis,
) : DeviceGuard {
    private var knownBoot: String? = null

    override fun beforeFlow(): DeviceCheck {
        val start = now()
        var pathOkSince: Long? = null
        var waiting: String
        var boot: String?
        while (true) {
            boot = bootId()
            waiting = when {
                boot == null -> "adb no responde"
                !bootCompleted() -> "Android todavía está arrancando"
                !hasPackage() -> "Android todavía no ve $packageName (¿se desinstaló?)"
                else -> {
                    val since = pathOkSince ?: now().also { pathOkSince = it }
                    if (resolvesActivity() || now() - since >= activityGrace.inWholeMilliseconds) break else "Android todavía no resuelve $packageName"
                }
            }
            if (now() - start >= readyTimeout.inWholeMilliseconds) {
                return DeviceCheck(
                    problem = "$serial no estuvo listo en $readyTimeout: $waiting. No es un defecto de la app: " +
                        "espera a que el dispositivo termine de arrancar, o reinícialo, y vuelve a correr.",
                )
            }
            sleep(pollInterval)
        }
        val restarted = knownBoot != null && boot != knownBoot
        knownBoot = boot
        val notes = mutableListOf<String>()
        val problem = prepareScreen(notes)
        return DeviceCheck(problem, restarted, notes)
    }

    override fun restartedDuringFlow(): Boolean {
        repeat(3) { attempt ->
            val boot = bootId()
            if (boot != null) {
                val changed = knownBoot != null && boot != knownBoot
                if (changed) knownBoot = boot
                return changed
            }
            if (attempt < 2) sleep(1.seconds)
        }
        // Sin respuesta de adb al terminar: casi siempre es un reinicio en curso. La próxima revisión lo confirma.
        return true
    }

    /** null si la pantalla quedó encendida y sin keyguard; si no, por qué no se puede seguir. */
    private fun prepareScreen(notes: MutableList<String>): String? {
        if (!awake()) {
            shell("input keyevent KEYCODE_WAKEUP")
            notes += "la pantalla estaba apagada: la encendí"
            sleep(1.seconds)
        }
        if (keyguardShowing() != true) return null
        shell("wm dismiss-keyguard")
        sleep(1.seconds)
        if (keyguardShowing() != true) {
            notes += "quité el bloqueo de pantalla (deslizar)"
            return null
        }
        return "$serial tiene un bloqueo de pantalla con PIN, patrón o contraseña, y testigo no puede desbloquearlo. " +
            "Desbloquéalo, o quítalo en el dispositivo de pruebas (adb -s $serial shell locksettings clear --old <PIN>) y vuelve a correr."
    }

    private fun shell(command: String): CommandResult? =
        try {
            adb.shell(serial, command, 20.seconds)
        } catch (e: TestigoException) {
            null
        }

    private fun bootId(): String? =
        shell("cat /proc/sys/kernel/random/boot_id")?.takeIf { it.ok }?.stdout?.trim()?.takeIf { it.isNotEmpty() }

    private fun bootCompleted(): Boolean = shell("getprop sys.boot_completed")?.stdout?.trim() == "1"

    private fun hasPackage(): Boolean = shell("pm path $packageName")?.let { it.ok && "package:" in it.stdout } == true

    /** `resolve-activity` contesta `No activity found` con código 0 cuando no resuelve: se mira el contenido. */
    private fun resolvesActivity(): Boolean {
        val result = shell("cmd package resolve-activity --brief $packageName") ?: return false
        return result.ok && result.stdout.lines().any { Regex("^[\\w.$]+/\\S+$").matches(it.trim()) }
    }

    /** Si no se puede saber, se asume despierto: no se le manda una tecla a un dispositivo que lo está. */
    private fun awake(): Boolean {
        val states = shell("dumpsys power | grep mWakefulness=")?.stdout.orEmpty()
        val values = Regex("mWakefulness=(\\w+)").findAll(states).map { it.groupValues[1] }.toList()
        return values.none { it in ASLEEP }
    }

    /** null si el dispositivo no lo dice. */
    private fun keyguardShowing(): Boolean? {
        val policy = shell("dumpsys window policy | grep showing=")?.stdout.orEmpty()
        return Regex("\\bshowing=(true|false)").find(policy)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private companion object {
        /** `mWakefulness` sale como nombre (`Asleep`) y como número (`0`) según la sección del dumpsys. */
        val ASLEEP = setOf("Asleep", "Dozing", "0", "3")
    }
}
