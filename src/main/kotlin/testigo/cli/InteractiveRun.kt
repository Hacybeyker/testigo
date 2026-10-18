package testigo.cli

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.interactiveMultiSelectList
import com.github.ajalt.mordant.input.interactiveSelectList
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.mordant.widgets.SelectList
import com.github.ajalt.mordant.widgets.Text
import testigo.artifact.BuildFinder
import testigo.config.ConfigEdit
import testigo.config.ConfigPaths
import testigo.config.LoadedProject
import testigo.config.NetworkMode
import testigo.config.Profile
import testigo.config.ProjectConfig
import testigo.config.RunOptions
import testigo.maestro.FlowDiscovery
import testigo.maestro.FlowFile
import testigo.maestro.FlowHeaderReader
import testigo.util.displayFrom
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Una opción de una lista de selección: una sola línea. [title] la identifica: no se repite en una lista. */
data class Choice(val title: String, val selected: Boolean = false)

/** Las preguntas del modo interactivo. Una interfaz para poder probar sin terminal. null: el usuario canceló. */
interface Asker {
    fun one(title: String, choices: List<Choice>, cursor: Int = 0): Int?

    fun many(title: String, choices: List<Choice>): List<Int>?

    fun text(title: String): String?

    /** Deja a la vista lo que se eligió, como `✔ Captura   red, logs`. */
    fun answered(label: String, value: String)
}

/**
 * Listas de selección de Mordant, una línea por opción y las teclas en español. En las de selección múltiple,
 * Espacio marca o desmarca (Mordant usa `x` por defecto). Ctrl+C cancela; Esc solo limpia la búsqueda.
 * Las teclas van bajo el título, en el color normal del texto: las de Mordant van en blanco y no se ven con fondo claro.
 */
class MordantAsker(private val terminal: Terminal) : Asker {

    override fun one(title: String, choices: List<Choice>, cursor: Int): Int? {
        val filterable = choices.size > FILTER_FROM
        val picked = terminal.interactiveSelectList {
            title(heading(title, keys(multi = false, filterable)))
            choices.forEach { addEntry(it.title) }
            startingCursorIndex(cursor)
            filterable(filterable)
            showInstructions(false)
        } ?: return null
        return choices.indexOfFirst { it.title == picked }.takeIf { it >= 0 }
    }

    override fun many(title: String, choices: List<Choice>): List<Int>? {
        val filterable = choices.size > FILTER_FROM
        val picked = terminal.interactiveMultiSelectList {
            title(heading(title, keys(multi = true, filterable)))
            // Sin descripción: con "" Mordant deja una línea en blanco bajo cada opción.
            choices.forEach { addEntry(SelectList.Entry(it.title, selected = it.selected)) }
            filterable(filterable)
            keyToggle(KeyboardEvent(" "))
            showInstructions(false)
        } ?: return null
        return picked.mapNotNull { title -> choices.indexOfFirst { it.title == title }.takeIf { it >= 0 } }
    }

    override fun text(title: String): String? = terminal.prompt(title)?.trim()?.takeIf { it.isNotEmpty() }

    override fun answered(label: String, value: String) = terminal.println("✔ ${label.padEnd(LABEL_WIDTH)}$value")

    internal companion object {
        const val FILTER_FROM = 7
        const val LABEL_WIDTH = 12

        /** Las teclas de una lista, como se muestran bajo el título. */
        fun keys(multi: Boolean, filterable: Boolean): List<Pair<String, String>> = buildList {
            if (multi) add("espacio" to "marca o desmarca")
            add("↑ ↓" to "mueve")
            add("enter" to if (multi) "confirma" else "elige")
            if (filterable) add("/" to "busca (esc sale)")
            add("ctrl+c" to "cancela")
        }

        /** El título en negrita y, debajo, las teclas en negrita con su descripción en el color normal del texto. */
        fun heading(title: String, keys: List<Pair<String, String>>) = Text(
            TextStyles.bold(title) + "\n" + keys.joinToString(" · ") { (key, description) -> "${TextStyles.bold(key)} $description" },
        )
    }
}

/**
 * Modo interactivo de `testigo run`: pregunta qué capturar, qué flows ejecutar y con qué app, y devuelve las mismas
 * opciones que darían los flags (`--record`, `--network`, `--logs`, `--flows`, `--app`). Así la corrida equivale
 * al comando equivalente que se imprime. No pregunta por perfiles: un perfil es solo un atajo de esos flags para
 * scripts y CI. Lo que tiene una sola opción no se pregunta. null: el usuario canceló.
 */
class InteractiveRun(
    private val asker: Asker,
    private val headerReader: FlowHeaderReader,
    private val cwd: Path,
    private val now: () -> Instant = Instant::now,
) {
    private class Cancelled : RuntimeException()

    private enum class Capture(val label: String, val detail: String) {
        VIDEO("Video", "graba la pantalla de cada flow"),
        NETWORK("Red", "requests y responses del backend, desde Chucker"),
        LOGS("Logs", "logcat de la app y crashes"),
    }

    fun ask(options: RunOptions, project: LoadedProject): RunOptions? = try {
        val captured = askCapture(project)
        val flows = askFlows(project)
        val app = askApp(project)
        options.copy(
            record = Capture.VIDEO in captured,
            network = if (networkAvailable(project)) {
                if (Capture.NETWORK in captured) NetworkMode.CHUCKER else NetworkMode.NONE
            } else {
                options.network
            },
            logs = Capture.LOGS in captured,
            flows = flows,
            app = app ?: options.app,
        )
    } catch (_: Cancelled) {
        null
    }

    /**
     * Lo que se respondió y tiene lugar en `testigo.yaml`: la app (`app.artifact`) y, si la captura elegida es la de un
     * perfil, ese perfil (`defaults.profile`). Los flows elegidos no: `flows` es una carpeta, no una lista.
     */
    fun saveable(answers: RunOptions, project: LoadedProject): List<ConfigEdit> {
        if (project.file == null) return emptyList()
        val edits = mutableListOf<ConfigEdit>()
        answers.app?.takeIf { it.isRegularFile() }?.let { app ->
            val configured = project.config.app.artifact?.let { project.dir.resolve(it).normalize() }
            if (configured != app.toAbsolutePath().normalize()) edits += ConfigEdit.artifact(ConfigPaths.relative(project.dir, app))
        }
        val current = project.config.defaults.profile ?: Profile.DEFAULT
        profileOf(answers, project)?.takeIf { it != current }?.let { edits += ConfigEdit.profile(it) }
        return edits
    }

    /** true: guardar. null: el usuario canceló. */
    fun askSave(edits: List<ConfigEdit>, file: String): Boolean? {
        val choices = listOf(Choice("Sí, guardar " + edits.joinToString(" · ")), Choice("No, solo para esta corrida"))
        val index = asker.one("¿Guardo esto en $file para la próxima vez?", choices) ?: return null
        return index == 0
    }

    /** El perfil que pide exactamente lo elegido; null si la combinación no es la de ningún perfil. */
    private fun profileOf(answers: RunOptions, project: LoadedProject): Profile? {
        val record = answers.record ?: return null
        val logs = answers.logs ?: return null
        val network = answers.network == NetworkMode.CHUCKER
        return Profile.entries.firstOrNull { profile ->
            profile.record == record && profile.logs == logs && (!networkAvailable(project) || profile.captureNetwork == network)
        }
    }

    /** `network.capture: none` en `testigo.yaml`: la app no trae Chucker, así que la red no se ofrece. */
    private fun networkAvailable(project: LoadedProject) = project.config.network.capture != NetworkMode.NONE

    /** Parte de lo que pide el perfil de `testigo.yaml` (o `dev`). */
    private fun askCapture(project: LoadedProject): Set<Capture> {
        val profile = project.config.defaults.profile ?: Profile.DEFAULT
        val offered = Capture.entries.filter { it != Capture.NETWORK || networkAvailable(project) }
        val width = offered.maxOf { it.label.length } + 3
        val choices = offered.map { capture ->
            val selected = when (capture) {
                Capture.VIDEO -> profile.record
                Capture.NETWORK -> profile.captureNetwork
                Capture.LOGS -> profile.logs
            }
            Choice(capture.label.padEnd(width) + capture.detail, selected)
        }
        val picked = asker.many("Qué capturar en cada flow", choices) ?: throw Cancelled()
        val captured = picked.map { offered[it] }.toSet()
        asker.answered(
            "Captura",
            if (captured.isEmpty()) "nada: solo el resultado y el reporte de Maestro" else offered.filter { it in captured }.joinToString(", ") { it.label.lowercase() },
        )
        return captured
    }

    /** Vacío: todos los flows de la carpeta de `testigo.yaml`, igual que sin `--flows`. */
    private fun askFlows(project: LoadedProject): List<Path> {
        val folder = project.config.flows ?: ProjectConfig.DEFAULT_FLOWS
        val root = project.dir.resolve(folder).normalize()
        val flows = FlowDiscovery.discover(listOf(root), headerReader).flows
            .sortedWith(compareBy<FlowFile>({ it.header.tags.firstOrNull() ?: "￿" }, { it.path.toString() }))
        if (flows.size <= 1) return emptyList()

        val base = if (root.isDirectory()) root else root.parent
        val names = flows.map { base.relativize(it.path).toString().replace('\\', '/') }
        val width = names.maxOf { it.length } + 3
        val choices = flows.mapIndexed { index, flow ->
            val tags = flow.header.tags.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "[", postfix = "]").orEmpty()
            Choice((names[index].padEnd(width) + tags).trimEnd(), selected = true)
        }
        val picked = asker.many("Flows a ejecutar · los .yaml de $folder (flows en testigo.yaml)", choices) ?: throw Cancelled()
        if (picked.isEmpty()) throw Cancelled()
        asker.answered("Flows", "${picked.size} de ${flows.size}: " + picked.sorted().joinToString(", ") { names[it] })
        return if (picked.size == flows.size) emptyList() else picked.sorted().map { flows[it].path }
    }

    /**
     * La app de `app.artifact` y los demás builds del mismo `build/outputs` (otras variantes o uno más nuevo).
     * null: la de `testigo.yaml`, porque no hay otra para elegir.
     */
    private fun askApp(project: LoadedProject): Path? {
        val configured = project.config.app.artifact?.let { project.dir.resolve(it).normalize() }?.takeIf { it.isRegularFile() }
        val outputs = outputsDir(configured ?: project.config.app.artifact?.let { project.dir.resolve(it).normalize() })
        val others = builds(outputs).filter { it != configured }
        val candidates = listOfNotNull(configured) + others
        if (candidates.isEmpty()) {
            val why = project.config.app.artifact?.let { "no existe $it" } ?: "no hay app.artifact en testigo.yaml"
            val typed = asker.text("Ruta del APK o AAB ($why)") ?: throw Cancelled()
            return cwd.resolve(typed).normalize().also { asker.answered("App", it.displayFrom(cwd)) }
        }
        if (candidates.size == 1) {
            val only = candidates.single()
            return if (only == configured) null else only.also { asker.answered("App", it.displayFrom(cwd)) }
        }

        val names = candidates.map { path -> outputs?.takeIf { path.startsWith(it) }?.relativize(path)?.toString() ?: path.displayFrom(cwd) }
        val width = names.maxOf { it.length } + 3
        val choices = candidates.mapIndexed { index, path ->
            val tag = if (path == configured) " · la de testigo.yaml" else ""
            Choice(names[index].padEnd(width) + describe(path) + tag)
        }
        val title = "App (APK o AAB)" + (outputs?.let { " · builds de ${it.displayFrom(cwd)}" } ?: "")
        val index = asker.one(title, choices, cursor = 0) ?: throw Cancelled()
        return candidates[index].also { asker.answered("App", it.displayFrom(cwd)) }
    }

    /** El `build/outputs` del módulo de la app, subiendo desde su ruta. */
    private fun outputsDir(artifact: Path?): Path? =
        generateSequence(artifact?.parent) { it.parent }
            .firstOrNull { it.name == "outputs" && it.parent?.name == "build" }
            ?.takeIf { it.isDirectory() }

    /** APKs y AABs de [outputs], del más nuevo al más viejo. Sin los de tests (`androidTest`) ni los sin firmar. */
    private fun builds(outputs: Path?): List<Path> {
        if (outputs == null) return emptyList()
        return Files.walk(outputs, MAX_DEPTH).use { paths ->
            paths.filter { it.isRegularFile() && (it.name.endsWith(".apk") || it.name.endsWith(".aab")) }
                .filter { "androidTest" !in it.name && "-unsigned" !in it.name }
                .toList()
        }.sortedByDescending { it.getLastModifiedTime().toMillis() }.take(MAX_BUILDS)
    }

    private fun describe(path: Path): String = BuildFinder.describe(path, now())

    private companion object {
        const val MAX_DEPTH = 5
        const val MAX_BUILDS = 15
    }
}
