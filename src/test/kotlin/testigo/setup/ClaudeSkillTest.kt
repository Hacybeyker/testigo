package testigo.setup

import testigo.TestigoException
import testigo.setup.ClaudeSkill.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

@DisabledOnOs(OS.WINDOWS)
class ClaudeSkillTest {

    @TempDir
    lateinit var tmp: Path

    private val home get() = tmp.resolve("home")
    private val app get() = tmp.resolve("testigo-0.1.0")
    private val target get() = home.resolve(".claude/skills/testigo-flows")
    private val backups get() = home.resolve(".testigo/backups")

    /** La skill que trae el zip, con un script ejecutable. */
    private fun source(): Path {
        val dir = app.resolve("skills/testigo-flows")
        dir.resolve("scripts").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: testigo-flows\n---\n")
        dir.resolve("scripts/mapa.py").writeText("print('mapa')\n")
        dir.resolve("scripts/mapa.py").toFile().setExecutable(true)
        dir.resolve(".DS_Store").writeText("basura de Finder")
        return dir
    }

    private fun status(): Status? = ClaudeSkill.inspect(emptyMap(), home, app)?.status

    @Test
    fun `sin Claude Code o sin la skill en esta instalacion no hay nada que ofrecer`() {
        source()
        assertNull(status(), "sin ~/.claude no se ofrece")

        home.resolve(".claude").createDirectories()
        assertNull(ClaudeSkill.inspect(emptyMap(), home, tmp.resolve("otra-instalacion")))
        assertEquals(Status.MISSING, status())

        val config = tmp.resolve("config-claude").createDirectories()
        val custom = ClaudeSkill.inspect(mapOf("CLAUDE_CONFIG_DIR" to config.toString()), home, app)
        assertEquals(config.resolve("skills/testigo-flows"), custom!!.target)
    }

    @Test
    fun `copia la skill con los scripts ejecutables, sin basura, y despues queda al dia`() {
        val source = source()
        home.resolve(".claude").createDirectories()

        assertNull(ClaudeSkill.install(source, target, backups), "no había otra que respaldar")

        assertTrue(target.resolve("scripts/mapa.py").isExecutable())
        assertFalse(target.resolve(".DS_Store").exists())
        assertEquals(Status.CURRENT, status())
        assertEquals(listOf("testigo-flows"), target.parent.listDirectoryEntries().map { it.fileName.toString() }, "no deja la carpeta temporal")
    }

    @Test
    fun `una copia distinta se actualiza y la anterior queda respaldada fuera de skills`() {
        val source = source()
        target.createDirectories().resolve("SKILL.md").writeText("editada a mano")
        assertEquals(Status.OUTDATED, status())

        val backup = ClaudeSkill.install(source, target, backups, LocalDateTime.of(2026, 10, 19, 11, 30, 0))

        assertEquals(backups.resolve("testigo-flows-20261019-113000"), backup)
        assertEquals("editada a mano", backup!!.resolve("SKILL.md").readText())
        assertEquals(Status.CURRENT, status())
        assertEquals(listOf("testigo-flows"), target.parent.listDirectoryEntries().map { it.fileName.toString() }, "Claude Code no ve dos skills iguales")
    }

    @Test
    fun `un enlace lo mantiene quien lo creo`() {
        val source = source()
        Files.createSymbolicLink(target.also { it.parent.createDirectories() }, source)

        assertEquals(Status.LINK, status())
        assertThrows(TestigoException::class.java) { ClaudeSkill.install(source, target, backups) }
        assertTrue(Files.isSymbolicLink(target))
    }
}
