package testigo.skill

/** El Python de la máquina, para las pruebas de los scripts de la skill. */
internal object Python {
    /** `python3` o `python`; null si no hay ninguno. */
    fun find(): String? = listOf("python3", "python").firstOrNull { name ->
        runCatching { ProcessBuilder(name, "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
    }
}
