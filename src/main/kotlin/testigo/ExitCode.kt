package testigo

/** Códigos de salida del runner. Ver "Códigos de salida" en docs/USO.md. */
enum class ExitCode(val code: Int) {
    /** Todos los flows pasaron. */
    OK(0),

    /** Al menos un flow falló. */
    FLOW_FAILED(1),

    /** Error de entorno o configuración. */
    ENVIRONMENT(2),

    /** Violación de la política local-only. */
    POLICY(3),

    /** El leak scan encontró datos sensibles en la evidencia. */
    LEAK(4),

    /** Se canceló una pregunta del modo interactivo: no se ejecutó nada. */
    CANCELLED(130),
}

/** Error esperado: se muestra al usuario sin stacktrace y el proceso termina con [exitCode]. */
class TestigoException(
    message: String,
    val exitCode: ExitCode = ExitCode.ENVIRONMENT,
) : RuntimeException(message)
