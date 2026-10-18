package testigo.maestro

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** Bloque de configuración de un flow de Maestro: lo que va antes de `---`. Solo lo que el runner usa. */
@Serializable
data class FlowHeader(
    val appId: String? = null,
    val name: String? = null,
    val tags: List<String> = emptyList(),
)

data class FlowFile(val path: Path, val header: FlowHeader)

fun interface FlowHeaderReader {
    fun read(path: Path): FlowHeader
}

data class FlowDiscoveryResult(
    val flows: List<FlowFile>,
    val missingRoots: List<Path>,
    /** Flows cuyo header no se pudo leer, con el motivo. */
    val unreadable: List<Pair<Path, String>>,
)

/**
 * Encuentra los flows a ejecutar dentro de las rutas indicadas.
 * Una ruta a un archivo se toma tal cual; una carpeta se recorre completa, excepto:
 * - carpetas `subflows/`: son piezas reutilizables, no casos de prueba;
 * - `config.yaml`: es la configuración de workspace de Maestro;
 * - archivos y carpetas ocultos.
 */
object FlowDiscovery {
    private val EXTENSIONS = setOf("yaml", "yml")
    private val IGNORED_FILES = setOf("config.yaml", "config.yml")
    private const val SUBFLOWS_DIR = "subflows"

    fun discover(roots: List<Path>, reader: FlowHeaderReader): FlowDiscoveryResult {
        val missing = mutableListOf<Path>()
        val files = linkedSetOf<Path>()
        for (root in roots) {
            when {
                root.isRegularFile() -> files.add(root.toAbsolutePath().normalize())
                root.isDirectory() -> files.addAll(walk(root))
                else -> missing.add(root)
            }
        }

        val unreadable = mutableListOf<Pair<Path, String>>()
        val flows = files.map { path ->
            val header = try {
                reader.read(path)
            } catch (e: Exception) {
                unreadable += path to (e.message ?: e::class.simpleName.orEmpty())
                FlowHeader()
            }
            FlowFile(path, header)
        }
        return FlowDiscoveryResult(flows, missing, unreadable)
    }

    /** Mismo criterio que Maestro con includeTags/excludeTags: basta con un tag en común. */
    fun filterByTags(flows: List<FlowFile>, include: List<String>, exclude: List<String>): List<FlowFile> =
        flows.filter { flow ->
            val tags = flow.header.tags
            (include.isEmpty() || tags.any { it in include }) && tags.none { it in exclude }
        }

    internal fun isFlowFile(relative: Path): Boolean {
        val name = relative.fileName?.toString() ?: return false
        if (name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) return false
        if (name.lowercase() in IGNORED_FILES) return false
        return relative.none { part -> part.toString() == SUBFLOWS_DIR || part.toString().startsWith(".") }
    }

    private fun walk(root: Path): List<Path> {
        val base = root.toAbsolutePath().normalize()
        return Files.walk(base).use { stream ->
            stream
                .filter { it.isRegularFile() && isFlowFile(base.relativize(it)) }
                .sorted()
                .toList()
        }
    }
}

/** Separa el header del flow sin interpretar los comandos. */
object FlowHeaderText {
    fun extract(text: String): String {
        val lines = text.lines()
        val separator = lines.indexOfFirst { it.trim() == "---" }
        return if (separator < 0) "" else lines.take(separator).joinToString("\n")
    }
}
