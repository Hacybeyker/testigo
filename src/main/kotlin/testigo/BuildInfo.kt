package testigo

/** La versión de testigo. Sale de `version` en build.gradle.kts: la tarea generateBuildInfo genera [GENERATED_VERSION]. */
object BuildInfo {
    const val VERSION = GENERATED_VERSION
}
