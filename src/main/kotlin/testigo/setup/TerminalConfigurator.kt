package testigo.setup

import testigo.TestigoException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Qué se escribió. [profileError]: no se pudo tocar el perfil (archivo de solo lectura, una política de la empresa). */
data class TerminalResult(val envFile: Path, val profile: Path?, val backup: Path?, val profileChanged: Boolean, val profileError: String?)

/**
 * Deja el entorno de testigo en la terminal sin root: escribe el archivo de entorno y, si la terminal no lo carga sola
 * (fish sí), agrega al final del perfil un bloque marcado que lo carga. La primera vez guarda una copia del perfil.
 */
object TerminalConfigurator {

    fun apply(s: MachineState, entries: List<EnvEntry>): TerminalResult {
        val envFile = s.shell.envFile
        try {
            envFile.parent.createDirectories()
            envFile.writeText(SetupPlan.rendered(s, entries))
        } catch (e: IOException) {
            throw TestigoException("No pude escribir ${s.show(envFile)}: ${e.message ?: e.javaClass.simpleName}")
        }
        val profile = s.shell.profile ?: return TerminalResult(envFile, null, null, profileChanged = false, profileError = null)
        return try {
            val existing = if (profile.isRegularFile()) profile.readText() else null
            val updated = ShellEnv.upsert(existing, ShellEnv.block(envFile, s.shellHome))
            if (updated == existing) return TerminalResult(envFile, profile, null, profileChanged = false, profileError = null)
            val backup = existing?.let {
                profile.resolveSibling(profile.name + ShellEnv.BACKUP_SUFFIX).also { copy ->
                    if (!copy.exists()) Files.copy(profile, copy)
                }
            }
            profile.parent?.createDirectories()
            profile.writeText(updated)
            TerminalResult(envFile, profile, backup, profileChanged = true, profileError = null)
        } catch (e: IOException) {
            TerminalResult(envFile, profile, null, profileChanged = false, profileError = e.message ?: e.javaClass.simpleName)
        }
    }
}
