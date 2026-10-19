package testigo.setup

import testigo.TestigoException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readSymbolicLink

/**
 * La skill `testigo-flows` de Claude Code: la que trae esta instalación de testigo (`skills/`) y su copia en
 * `~/.claude/skills`. `testigo setup` la copia solo si se lo pides, y solo si Claude Code ya está en la máquina.
 */
data class ClaudeSkill(val source: Path, val target: Path, val status: Status) {

    enum class Status {
        /** No está en `~/.claude/skills`. */
        MISSING,

        /** Igual a la de esta instalación. */
        CURRENT,

        /** Distinta: de otra versión de testigo o editada a mano. */
        OUTDATED,

        /** Un enlace (por ejemplo, a un clon del repo): la mantiene quien lo creó. */
        LINK,
    }

    companion object {
        const val NAME = "testigo-flows"
        private val IGNORED = setOf(".DS_Store")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /**
         * null si esta instalación no trae la skill o no hay Claude Code: su carpeta es `CLAUDE_CONFIG_DIR` o `~/.claude`,
         * y solo cuenta si ya existe.
         */
        fun inspect(env: Map<String, String>, home: Path, appHome: Path?): ClaudeSkill? {
            val source = appHome?.resolve("skills/$NAME")?.takeIf { it.resolve("SKILL.md").isRegularFile() } ?: return null
            val config = env["CLAUDE_CONFIG_DIR"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: home.resolve(".claude")
            if (!config.isDirectory()) return null
            val target = config.resolve("skills/$NAME")
            val status = when {
                Files.isSymbolicLink(target) -> Status.LINK
                !target.exists(LinkOption.NOFOLLOW_LINKS) -> Status.MISSING
                target.isDirectory() && sameFiles(source, target) -> Status.CURRENT
                else -> Status.OUTDATED
            }
            return ClaudeSkill(source, target, status)
        }

        /** Mismos archivos con el mismo contenido. */
        internal fun sameFiles(a: Path, b: Path): Boolean {
            val left = files(a)
            val right = files(b)
            return left.keys == right.keys && left.all { (relative, file) -> Files.mismatch(file, right.getValue(relative)) == -1L }
        }

        /**
         * Copia la skill a [target]. Si ya había una, primero la mueve a [backups] (con fecha) y devuelve dónde quedó: puede
         * tener cambios hechos a mano. Copia a una carpeta temporal al lado y la renombra al final, para no dejar una skill a
         * medias. Nunca toca un enlace.
         */
        fun install(source: Path, target: Path, backups: Path, now: LocalDateTime = LocalDateTime.now()): Path? {
            if (Files.isSymbolicLink(target)) throw TestigoException("${target.fileName} es un enlace: no lo reemplazo.")
            val parent = target.parent.createDirectories()
            val staging = parent.resolve(".$NAME.testigo-tmp")
            try {
                deleteTree(staging)
                copyTree(source, staging)
                val backup = if (target.exists(LinkOption.NOFOLLOW_LINKS)) {
                    val destination = backups.createDirectories().resolve("$NAME-${now.format(STAMP)}")
                    move(target, destination)
                    destination
                } else {
                    null
                }
                Files.move(staging, target)
                return backup
            } catch (e: IOException) {
                throw TestigoException("No pude copiar la skill a $target: ${e.message}")
            } finally {
                runCatching { deleteTree(staging) }
            }
        }

        private fun files(root: Path): Map<String, Path> = Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.fileName.toString() !in IGNORED }.toList()
        }.associateBy { root.relativize(it).toString().replace('\\', '/') }

        /** Copia con permisos: los scripts siguen siendo ejecutables. */
        private fun copyTree(source: Path, target: Path) {
            Files.walk(source).use { paths ->
                paths.filter { it.fileName.toString() !in IGNORED }.forEach { path ->
                    val destination = target.resolve(source.relativize(path).toString())
                    if (path.isDirectory()) {
                        destination.createDirectories()
                    } else {
                        Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        }

        /** Un rename si se puede; si no (otro disco), copia y borra. */
        private fun move(from: Path, to: Path) {
            try {
                Files.move(from, to)
            } catch (e: IOException) {
                copyTree(from, to)
                deleteTree(from)
            }
        }

        private fun deleteTree(path: Path) {
            if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return
            Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }

        /** Para mostrar a dónde apunta un enlace. */
        fun linkTarget(target: Path): Path? = runCatching { target.readSymbolicLink() }.getOrNull()
    }
}
