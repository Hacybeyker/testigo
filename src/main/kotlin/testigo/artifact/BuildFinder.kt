package testigo.artifact

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.EnumSet
import java.util.Locale
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Builds de Gradle cerca de una suite: los APKs y AABs de cada `build/outputs` dentro de la carpeta y de sus carpetas
 * hermanas (la suite suele estar junto al repo de la app, o dentro de él). Sin los de tests ni los sin firmar.
 */
object BuildFinder {
    private val SKIP = setOf("node_modules", "Pods", "DerivedData", "venv", "Library")
    private const val MAX_DEPTH = 5
    private const val MAX_DIRS = 5_000

    fun near(dir: Path, home: Path = Path.of(System.getProperty("user.home")), max: Int = 10): List<Path> {
        val base = dir.toAbsolutePath().normalize()
        val parent = base.parent?.takeIf { it.parent != null && !(home.startsWith(it) && it != home) }
        val roots = listOfNotNull(base, parent).distinct()
        val outputs = roots.flatMap { outputsUnder(it) }.distinct()
        return outputs.flatMap { builds(it) }.distinct()
            .sortedByDescending { it.getLastModifiedTime().toMillis() }
            .take(max)
    }

    /** `APK · hace 2 h · 18 MB` */
    fun describe(path: Path, now: Instant = Instant.now()): String {
        val age = Duration.between(path.getLastModifiedTime().toInstant(), now).let { d ->
            when {
                d.toMinutes() < 1 -> "recién"
                d.toHours() < 1 -> "hace ${d.toMinutes()} min"
                d.toDays() < 1 -> "hace ${d.toHours()} h"
                else -> "hace ${d.toDays()} d"
            }
        }
        val format = if (path.name.endsWith(".aab", ignoreCase = true)) "AAB" else "APK"
        val size = "%.0f MB".format(Locale.ROOT, path.fileSize() / 1_048_576.0)
        return "$format · $age · $size"
    }

    private fun outputsUnder(root: Path): List<Path> {
        val found = mutableListOf<Path>()
        var visited = 0
        Files.walkFileTree(
            root,
            EnumSet.noneOf(java.nio.file.FileVisitOption::class.java),
            MAX_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++visited > MAX_DIRS) return FileVisitResult.TERMINATE
                    val name = dir.name
                    if (dir != root && (name.startsWith(".") || name in SKIP)) return FileVisitResult.SKIP_SUBTREE
                    if (dir.parent?.name == "build") {
                        if (name == "outputs") found.add(dir)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return found
    }

    private fun builds(outputs: Path): List<Path> = runCatching {
        Files.walk(outputs, 4).use { paths ->
            paths.filter { it.isRegularFile() && ArtifactFormat.of(it.name) != null }
                .filter { "androidTest" !in it.name && "-unsigned" !in it.name }
                .toList()
        }
    }.getOrDefault(emptyList())
}
