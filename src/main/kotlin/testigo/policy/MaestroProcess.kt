package testigo.policy

import testigo.TestigoException
import testigo.ExitCode

/**
 * Arma los procesos de Maestro que lanza el runner. Es lista blanca: solo `maestro test` y `maestro --version`,
 * siempre con [MaestroEnvironment]. `cloud`, `login`, `chat`, `bugreport`, `record` y el resto nunca se ejecutan.
 */
object MaestroProcess {
    private val ALLOWED_SUBCOMMANDS = setOf("test")

    /** Flags que mandan logs, comandos o capturas a los servidores de Maestro. */
    private val FORBIDDEN_FLAGS = setOf("--analyze", "--api-key", "--apiKey", "--api-url", "--apiUrl")

    /** Opciones globales de Maestro que van antes del subcomando y llevan un valor. */
    private val GLOBAL_OPTIONS_WITH_VALUE = setOf("--device", "--udid", "--host", "--port", "-p", "--platform")

    fun builder(maestro: String, args: List<String>): ProcessBuilder {
        check(args)
        return ProcessBuilder(listOf(maestro) + args).also { MaestroEnvironment.applyTo(it.environment()) }
    }

    /** Lanza [TestigoException] con código 3 si la invocación no está permitida. */
    fun check(args: List<String>) {
        val flag = args.firstOrNull { arg -> FORBIDDEN_FLAGS.any { arg == it || arg.startsWith("$it=") } }
        if (flag != null) {
            throw violation("el runner nunca pasa `${flag.substringBefore('=')}` a Maestro: envía datos a sus servidores.")
        }
        val subcommand = subcommand(args)
        when {
            subcommand == null && args == listOf("--version") -> Unit
            subcommand == null -> throw violation("invocación de Maestro no permitida: ${args.joinToString(" ")}")
            subcommand !in ALLOWED_SUBCOMMANDS -> throw violation("el runner solo ejecuta `maestro test`; `maestro $subcommand` no está permitido.")
        }
    }

    private fun subcommand(args: List<String>): String? {
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg in GLOBAL_OPTIONS_WITH_VALUE -> i += 2
                arg.startsWith("-") -> i += 1
                else -> return arg
            }
        }
        return null
    }

    private fun violation(message: String) = TestigoException("Política local-only: $message", ExitCode.POLICY)
}
