package testigo.report

import testigo.process.CommandRunner
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.time.Duration.Companion.seconds

/** Branch y commit del código que se probó. [ticket] sale del branch con `evidence.ticket_pattern`. */
data class GitInfo(
    /** Nombre de la carpeta raíz del repo. */
    val repo: String,
    /** null: HEAD suelto (detached). */
    val branch: String?,
    /** Hash corto; null en un repo sin commits. */
    val commit: String?,
    /** Había cambios sin commit: el build puede no corresponder al commit. */
    val dirty: Boolean,
    val ticket: String?,
) {
    /** `MiApp · feature/PROJ-123-login · a1b2c3d · con cambios sin commit` */
    val label: String
        get() = listOfNotNull(repo, branch ?: "HEAD suelto", commit, "con cambios sin commit".takeIf { dirty }).joinToString(" · ")
}

/**
 * Lee git localmente, sin red: `rev-parse` y `status`. Primero el repo del artefacto (el de la app, donde está el
 * branch del ticket) y, si no está en uno, el de la suite.
 */
object Git {

    fun read(dirs: List<Path>, ticketPattern: Regex?, runner: CommandRunner): GitInfo? =
        dirs.asSequence().mapNotNull { read(it, ticketPattern, runner) }.firstOrNull()

    private fun read(dir: Path, ticketPattern: Regex?, runner: CommandRunner): GitInfo? {
        val existing = generateSequence(dir.toAbsolutePath()) { it.parent }.firstOrNull { it.isDirectory() } ?: return null
        fun git(vararg args: String): String? =
            runCatching { runner.run(listOf("git", "--no-optional-locks", "-C", existing.toString()) + args, 10.seconds) }
                .getOrNull()
                ?.takeIf { it.ok }
                ?.stdout
                ?.trim()

        val root = git("rev-parse", "--show-toplevel")?.takeIf { it.isNotEmpty() } ?: return null
        val branch = git("rev-parse", "--abbrev-ref", "HEAD")?.takeIf { it.isNotEmpty() && it != "HEAD" }
        val commit = git("rev-parse", "--short=7", "HEAD")?.takeIf { it.isNotEmpty() }
        val dirty = git("status", "--porcelain")?.isNotEmpty() ?: false
        return GitInfo(
            repo = Path.of(root).fileName?.toString() ?: root,
            branch = branch,
            commit = commit,
            dirty = dirty,
            ticket = branch?.let { ticketPattern?.find(it)?.value },
        )
    }

    /** Sufijo de la carpeta de la corrida: `_PROJ-123_a1b2c3d`, con lo que haya. */
    fun runSuffix(git: GitInfo?): String =
        listOfNotNull(git?.ticket, git?.commit)
            .map { it.replace(Regex("[^A-Za-z0-9._-]+"), "-") }
            .joinToString("") { "_$it" }
}
