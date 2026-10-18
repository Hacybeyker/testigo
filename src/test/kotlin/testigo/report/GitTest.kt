package testigo.report

import testigo.process.CommandResult
import testigo.process.CommandRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.time.Duration

class GitTest {

    @TempDir
    lateinit var tmp: Path

    /** git simulado: [repos] son las carpetas que están en un repo; responde según el subcomando. */
    private class FakeGit(private val repos: Map<Path, String>, private val branch: String, private val status: String = "") : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            val dir = Path.of(command[command.indexOf("-C") + 1])
            val root = repos.entries.firstOrNull { dir.startsWith(it.key) } ?: return CommandResult(128, "", "fatal: not a git repository")
            return when (command.drop(command.indexOf("-C") + 2)) {
                listOf("rev-parse", "--show-toplevel") -> CommandResult(0, "${root.key}\n", "")
                listOf("rev-parse", "--abbrev-ref", "HEAD") -> CommandResult(0, "$branch\n", "")
                listOf("rev-parse", "--short=7", "HEAD") -> CommandResult(0, "${root.value}\n", "")
                listOf("status", "--porcelain") -> CommandResult(0, status, "")
                else -> CommandResult(1, "", "?")
            }
        }
    }

    @Test
    fun `toma el repo del artefacto, saca el ticket del branch y nunca escribe`() {
        val app = tmp.resolve("MiApp").createDirectories()
        val apkDir = app.resolve("app/build/outputs/apk/qa").createDirectories()
        val suite = tmp.resolve("suite").createDirectories()
        val git = FakeGit(mapOf(app to "a1b2c3d", suite to "9999999"), branch = "feature/PROJ-123-login", status = " M app/src/Main.kt\n")

        val info = Git.read(listOf(apkDir, suite), Regex("[A-Z]+-\\d+"), git)!!

        assertEquals(GitInfo("MiApp", "feature/PROJ-123-login", "a1b2c3d", dirty = true, ticket = "PROJ-123"), info)
        assertEquals("_PROJ-123_a1b2c3d", Git.runSuffix(info))
        assertEquals("MiApp · feature/PROJ-123-login · a1b2c3d · con cambios sin commit", info.label)
        assertEquals(true, git.commands.all { it.take(2) == listOf("git", "--no-optional-locks") })
    }

    @Test
    fun `si el artefacto no esta en un repo usa el de la suite, y sin repos no hay git`() {
        val suite = tmp.resolve("suite").createDirectories()
        val git = FakeGit(mapOf(suite to "9999999"), branch = "HEAD")

        val info = Git.read(listOf(tmp.resolve("descargas/app.apk"), suite), Regex("[A-Z]+-\\d+"), git)!!

        assertEquals(GitInfo("suite", null, "9999999", dirty = false, ticket = null), info)
        assertEquals("_9999999", Git.runSuffix(info))
        assertNull(Git.read(listOf(tmp.resolve("otro")), null, FakeGit(emptyMap(), "main")))
        assertEquals("", Git.runSuffix(null))
    }
}
