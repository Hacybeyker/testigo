package testigo.process

/**
 * Lo que hay que deshacer si la corrida se corta a la mitad (Ctrl+C o el proceso termina): el wrapper de video junto
 * al flow, los videos en el dispositivo, logcat corriendo y los datos crudos en carpetas temporales. Cada parte se
 * registra mientras existe y se quita al terminar bien; al cerrarse la JVM se ejecuta lo que quede, de lo último a
 * lo primero. Un fallo en una acción no impide las demás.
 */
object Cleanup {
    private val actions = LinkedHashMap<Any, () -> Unit>()
    private var hooked = false

    @Synchronized
    fun register(key: Any, action: () -> Unit) {
        if (!hooked) {
            Runtime.getRuntime().addShutdownHook(Thread(::runAll, "testigo-cleanup"))
            hooked = true
        }
        actions[key] = action
    }

    @Synchronized
    fun unregister(key: Any) {
        actions.remove(key)
    }

    /** Lo que sigue registrado, para probarlo sin cerrar la JVM. */
    @Synchronized
    internal fun pending(): Int = actions.size

    @Synchronized
    internal fun runAll() {
        val pending = actions.values.toList().asReversed()
        actions.clear()
        pending.forEach { action -> runCatching(action) }
    }
}
