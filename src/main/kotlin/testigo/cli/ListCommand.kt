package testigo.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.path
import testigo.TestigoException
import testigo.ExitCode
import testigo.config.ConfigLoader
import testigo.config.ProjectConfig
import testigo.config.ProjectLocator
import testigo.maestro.FlowDiscovery
import testigo.maestro.FlowFile
import testigo.maestro.YamlFlowHeaderReader
import testigo.util.displayFrom
import java.nio.file.Path
import kotlin.io.path.isDirectory

class ListCommand : CliktCommand(name = "list") {

    override fun help(context: Context): String =
        "Lista los flows de la suite con su nombre y sus tags. Con --tags y --exclude-tags muestra los mismos que " +
            "ejecutaría testigo run. No toca el dispositivo."

    private val config by option("--config", help = "Ruta a testigo.yaml. Por defecto se busca desde la carpeta actual hacia arriba.")
        .path()
    private val flows by option("--flows", help = "Flow o carpeta de flows; se puede repetir. Por defecto, flows de testigo.yaml o flows/.")
        .path()
        .multiple()
    private val tags by option("--tags", help = "Solo flows con alguno de estos tags, separados por coma.")
        .split(",")
    private val excludeTags by option("--exclude-tags", help = "Excluye flows con alguno de estos tags, separados por coma.")
        .split(",")

    override fun run() {
        val cwd = Path.of("").toAbsolutePath().normalize()
        val project = try {
            ConfigLoader.load(config, cwd)
        } catch (e: TestigoException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(e.exitCode.code)
        }
        val (roots, origin) = when {
            flows.isNotEmpty() -> flows.map { cwd.resolve(it).normalize() } to "--flows"
            project.config.flows != null -> listOf(project.dir.resolve(project.config.flows).normalize()) to "flows en ${ProjectLocator.FILE_NAME}"
            else -> listOf(project.dir.resolve(ProjectConfig.DEFAULT_FLOWS).normalize()) to "por defecto"
        }
        val discovery = FlowDiscovery.discover(roots, YamlFlowHeaderReader())
        val include = tags.cleaned()
        val exclude = excludeTags.cleaned()
        val selected = FlowDiscovery.filterByTags(discovery.flows, include, exclude)

        echo(FlowListRenderer.render(roots, origin, discovery.flows, selected, include, exclude, cwd))
        discovery.missingRoots.forEach { echo("! No existe la ruta de flows: ${it.displayFrom(cwd)}", err = true) }
        discovery.unreadable.forEach { (path, reason) -> echo("! No pude leer el header de ${path.displayFrom(cwd)}: $reason", err = true) }
        if (discovery.missingRoots.isNotEmpty()) throw ProgramResult(ExitCode.ENVIRONMENT.code)
    }

    private fun List<String>?.cleaned(): List<String> = orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** Texto de `testigo list`: una línea por flow (archivo, nombre y tags) y el conteo por tag. */
object FlowListRenderer {

    fun render(
        roots: List<Path>,
        origin: String,
        all: List<FlowFile>,
        selected: List<FlowFile>,
        include: List<String>,
        exclude: List<String>,
        cwd: Path,
    ): String = buildString {
        val base = roots.singleOrNull()?.takeIf { it.isDirectory() }
        val where = roots.joinToString(", ") { root -> root.displayFrom(cwd).let { if (root.isDirectory() && !it.endsWith("/")) "$it/" else it } }
        val filters = listOfNotNull(
            include.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "tags: "),
            exclude.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "sin: "),
        )
        val count = if (filters.isEmpty()) "${all.size}" else "${selected.size} de ${all.size} (${filters.joinToString(" · ")})"
        appendLine("Flows · $where ($origin) · $count")
        appendLine()
        if (selected.isEmpty()) {
            appendLine("  (ninguno)")
            return@buildString
        }

        val files = selected.map { flow ->
            val shown = if (base != null && flow.path.startsWith(base)) base.relativize(flow.path).toString() else flow.path.displayFrom(cwd)
            shown.replace('\\', '/')
        }
        val names = selected.map { it.header.name ?: it.path.fileName.toString().substringBeforeLast('.') }
        val fileWidth = files.maxOf { it.length } + 3
        val nameWidth = names.maxOf { it.length } + 3
        selected.forEachIndexed { index, flow ->
            val tags = flow.header.tags.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "[", postfix = "]").orEmpty()
            appendLine("  ${files[index].padEnd(fileWidth)}${names[index].padEnd(nameWidth)}$tags".trimEnd())
        }

        val counts = selected.flatMap { it.header.tags }.groupingBy { it }.eachCount().toSortedMap()
        appendLine()
        append(if (counts.isEmpty()) "Tags  ninguno" else "Tags  " + counts.entries.joinToString(" · ") { (tag, n) -> "$tag $n" })
    }
}
