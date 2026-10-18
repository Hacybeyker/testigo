package testigo.android

import testigo.process.CommandResult

/**
 * Cómo leer los archivos privados de una app, como su base de Chucker:
 * - con un build debuggable, `run-as <package>` (las rutas son relativas a la carpeta de la app);
 * - si no es debuggable, solo cuando adbd ya corre como root, como después de `adb root` en un emulador con imagen
 *   Google APIs (las rutas van completas, en `/data/data/<package>/`).
 *
 * El runner nunca ejecuta `adb root` por su cuenta: reinicia adbd y cortaría la conexión con el dispositivo.
 */
class AppFiles private constructor(val mode: Mode, private val packageName: String) {

    enum class Mode(val label: String) {
        RUN_AS("run-as"),
        ROOT("adb root"),
    }

    /** Los argumentos de `adb shell` o `adb exec-out` para ejecutar [args] con acceso a la carpeta de la app. */
    fun command(vararg args: String): List<String> = when (mode) {
        Mode.RUN_AS -> listOf("run-as", packageName) + args
        Mode.ROOT -> args.toList()
    }

    /** `databases/chucker.db` con run-as, o `/data/data/<package>/databases/chucker.db` con root. */
    fun path(relative: String): String = when (mode) {
        Mode.RUN_AS -> relative
        Mode.ROOT -> "/data/data/$packageName/$relative"
    }

    /** [files] es null si no hay acceso; [runAsError] es lo que respondió run-as si falló. */
    data class Access(val files: AppFiles?, val runAsError: String?)

    companion object {
        /** Prueba run-as y, si falla, si adbd corre como root. [shell] ejecuta `adb -s <serial> shell <args>`. */
        fun detect(packageName: String, shell: (List<String>) -> CommandResult): Access {
            val runAs = shell(listOf("run-as", packageName, "id"))
            if (runAs.ok && "uid=" in runAs.stdout) return Access(AppFiles(Mode.RUN_AS, packageName), null)
            val error = runAs.output.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "código ${runAs.exitCode}"
            val id = shell(listOf("id", "-u"))
            return if (id.ok && id.stdout.trim() == "0") {
                Access(AppFiles(Mode.ROOT, packageName), error)
            } else {
                Access(null, error)
            }
        }
    }
}
