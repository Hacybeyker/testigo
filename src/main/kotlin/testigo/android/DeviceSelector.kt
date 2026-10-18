package testigo.android

import testigo.TestigoException

/**
 * Elige el dispositivo de la corrida. Con `--device`, ese serial; sin él, el único listo.
 * Con más de uno conectado nunca adivina: pide `--device`, porque Maestro y adb podrían elegir distinto.
 */
object DeviceSelector {

    fun select(devices: List<AdbDevice>, requested: String?): AdbDevice {
        if (requested != null) {
            val device = devices.firstOrNull { it.serial == requested }
                ?: throw TestigoException("El dispositivo $requested no está conectado. ${connected(devices)}")
            if (!device.ready) throw notReady(device)
            return device
        }

        val ready = devices.filter { it.ready }
        return when {
            ready.size == 1 -> ready.single()
            ready.size > 1 -> throw TestigoException(
                "Hay ${ready.size} dispositivos conectados: elige uno con --device <serial>.\n" +
                    ready.joinToString("\n") { "  ${it.serial}" + (it.model?.let { model -> "  ($model)" } ?: "") },
            )
            devices.isNotEmpty() -> throw notReady(devices.first())
            else -> throw TestigoException(
                "No hay dispositivos conectados: inicia el emulador o conecta un teléfono con Depuración USB activada.",
            )
        }
    }

    private fun notReady(device: AdbDevice): TestigoException {
        val message = when (device.state) {
            "unauthorized" -> "${device.serial} no autorizó la depuración: acepta el aviso \"¿Permitir depuración USB?\" en el teléfono."
            "offline" -> "${device.serial} está offline: reconéctalo o reinicia adb con `adb kill-server`."
            else -> "${device.serial} no está listo (estado: ${device.state})."
        }
        return TestigoException(message)
    }

    private fun connected(devices: List<AdbDevice>): String =
        if (devices.isEmpty()) {
            "No hay ninguno conectado."
        } else {
            "Conectados: " + devices.joinToString(", ") { "${it.serial} (${it.state})" } + "."
        }
}
