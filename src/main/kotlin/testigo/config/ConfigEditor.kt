package testigo.config

import testigo.TestigoException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Un valor de testigo.yaml para guardar: `app.artifact`, `defaults.profile` y otros escalares. [applied] comprueba en el
 * archivo ya leído que el valor quedó como se quería.
 */
class ConfigEdit internal constructor(
    val key: String,
    /** Como se muestra: `../MiApp/app-debug.apk`. */
    val value: String,
    internal val yaml: String,
    internal val applied: (ProjectConfig) -> Boolean,
) {
    override fun toString(): String = "$key: $value"

    companion object {
        fun artifact(path: String) = ConfigEdit("app.artifact", path, YamlScalar.string(path)) { it.app.artifact == path }

        fun profile(profile: Profile) = ConfigEdit("defaults.profile", profile.cliName, profile.cliName) { it.defaults.profile == profile }

        fun videoBitrate(mbps: Int) = ConfigEdit("video.bitrate_mbps", "$mbps", "$mbps") { it.video.bitrateMbps == mbps }

        fun networkFormat(format: NetworkFormat) =
            ConfigEdit("network.format", format.cliName, format.cliName) { it.network.format == format }

        fun redact(enabled: Boolean) = ConfigEdit("redact.enabled", "$enabled", "$enabled") { it.redact.enabled == enabled }

        fun evidenceDir(path: String) = ConfigEdit("evidence.dir", path, YamlScalar.string(path)) { it.evidence.dir == path }

        fun flows(path: String) = ConfigEdit("flows", path, YamlScalar.string(path)) { it.flows == path }
    }
}

/**
 * Cambia valores de testigo.yaml línea por línea: conserva los comentarios, el orden y el formato del resto del archivo.
 * Solo toca escalares de primer o segundo nivel (`flows`, `app.artifact`), en bloque (`app:` y debajo) o en una línea
 * (`maestro: { version: "2.11.0" }`). Antes de escribir, lee el resultado con el parser real: si no queda igual a lo
 * pedido, no escribe nada.
 */
object ConfigEditor {

    fun save(file: Path, edits: List<ConfigEdit>, read: (String) -> ProjectConfig = { text -> parse(text, file) }) {
        if (edits.isEmpty()) return
        val updated = apply(file.readText(), edits)
        val config = try {
            read(updated)
        } catch (e: TestigoException) {
            throw TestigoException("${file.name} quedaría inválido; no lo cambié: ${e.message}")
        }
        edits.firstOrNull { !it.applied(config) }?.let {
            throw TestigoException("No pude dejar ${it.key} en ${file.name}; no lo cambié. Edítalo a mano: ${it.key}: ${it.value}")
        }
        val temp = Files.createTempFile(file.toAbsolutePath().parent, ".${file.name}.", ".tmp")
        try {
            temp.writeText(updated)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun parse(text: String, file: Path): ProjectConfig =
        ConfigLoader.parse(text, file.toString()).also { ProjectValidator.validate(it, file.toString()) }

    fun apply(text: String, edits: List<ConfigEdit>): String {
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split(Regex("\r?\n")).toMutableList()
        edits.forEach { edit ->
            val parts = edit.key.split('.')
            when (parts.size) {
                1 -> setTop(lines, parts[0], edit.yaml)
                2 -> setChild(lines, parts[0], parts[1], edit.yaml)
                else -> throw TestigoException("No sé editar ${edit.key}.")
            }
        }
        return lines.joinToString(newline)
    }

    private fun keyLine(key: String) = Regex("^${Regex.escape(key)}:(?=\\s|$)")

    private fun indentOf(line: String) = line.length - line.trimStart(' ').length

    private fun isContent(line: String) = line.isNotBlank() && !line.trimStart().startsWith("#")

    private fun setTop(lines: MutableList<String>, key: String, yaml: String) {
        val index = lines.indexOfFirst { keyLine(key).containsMatchIn(it) }
        if (index < 0) {
            append(lines, listOf("$key: $yaml"))
            return
        }
        val (value, _) = splitComment(lines[index].substring(key.length + 1))
        if (value.isEmpty() && nextContentIndented(lines, index, 0)) throw TestigoException("$key no es un valor simple en testigo.yaml: edítalo a mano.")
        lines[index] = replaceValue(lines[index], key.length + 1, yaml)
    }

    private fun setChild(lines: MutableList<String>, parent: String, key: String, yaml: String) {
        val p = lines.indexOfFirst { keyLine(parent).containsMatchIn(it) }
        if (p < 0) {
            append(lines, listOf("$parent:", "  $key: $yaml"))
            return
        }
        val (inline, comment) = splitComment(lines[p].substring(parent.length + 1))
        if (inline.isNotEmpty()) {
            if (!(inline.startsWith("{") && inline.endsWith("}"))) throw TestigoException("$parent no es un bloque en testigo.yaml: edítalo a mano.")
            lines[p] = "$parent: " + setInFlowMap(inline, key, yaml, parent) + comment?.let { "   $it" }.orEmpty()
            return
        }
        // El bloque termina en la primera línea con contenido al margen izquierdo.
        var end = p + 1
        while (end < lines.size && (!isContent(lines[end]) || indentOf(lines[end]) > 0)) end++
        val children = (p + 1 until end).filter { isContent(lines[it]) && indentOf(lines[it]) > 0 }
        val childIndent = children.firstOrNull()?.let { indentOf(lines[it]) } ?: 2
        val child = children.firstOrNull { indentOf(lines[it]) == childIndent && keyLine(key).containsMatchIn(lines[it].substring(childIndent)) }
        if (child != null) {
            val keyEnd = childIndent + key.length + 1
            val (value, _) = splitComment(lines[child].substring(keyEnd))
            if (value.isEmpty() && nextContentIndented(lines, child, childIndent)) throw TestigoException("$parent.$key no es un valor simple en testigo.yaml: edítalo a mano.")
            lines[child] = replaceValue(lines[child], keyEnd, yaml)
            return
        }
        val after = children.lastOrNull() ?: p
        lines.add(after + 1, " ".repeat(childIndent) + "$key: $yaml")
    }

    /** La línea siguiente con contenido está más adentro: el valor es un bloque o una lista, no un escalar. */
    private fun nextContentIndented(lines: List<String>, index: Int, indent: Int): Boolean {
        val next = (index + 1 until lines.size).firstOrNull { isContent(lines[it]) } ?: return false
        return indentOf(lines[next]) > indent || lines[next].trimStart().startsWith("- ")
    }

    /** Agrega un bloque al final, separado por una línea en blanco. */
    private fun append(lines: MutableList<String>, block: List<String>) {
        // split deja un "" final si el archivo terminaba en salto de línea.
        val trailing = lines.isNotEmpty() && lines.last().isEmpty()
        if (trailing) lines.removeAt(lines.size - 1)
        if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
        lines.addAll(block)
        lines.add("")
    }

    /** Cambia el valor después de `clave:` y deja el comentario de la línea en su columna, si cabe. */
    private fun replaceValue(line: String, keyEnd: Int, yaml: String): String {
        val prefix = line.substring(0, keyEnd)
        val rest = line.substring(keyEnd)
        val base = "$prefix $yaml"
        val start = commentStart(rest) ?: return base
        val column = keyEnd + start
        val pad = if (base.length < column) column - base.length else COMMENT_GAP
        return base + " ".repeat(pad) + rest.substring(start)
    }

    private const val COMMENT_GAP = 3

    /** Valor y comentario (`# ...`) de lo que sigue a `clave:`. */
    internal fun splitComment(rest: String): Pair<String, String?> {
        val start = commentStart(rest) ?: return rest.trim() to null
        return rest.substring(0, start).trim() to rest.substring(start)
    }

    /** Dónde empieza el comentario. Un `#` dentro de comillas o pegado a una palabra no es comentario. */
    private fun commentStart(rest: String): Int? {
        var quote: Char? = null
        rest.forEachIndexed { i, c ->
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c == '#' && (i == 0 || rest[i - 1].isWhitespace()) -> return i
            }
        }
        return null
    }

    /** `{ version: "2.11.0" }` con [key] cambiado o agregado. Solo mapas simples, sin anidar. */
    private fun setInFlowMap(map: String, key: String, yaml: String, parent: String): String {
        val inner = map.removePrefix("{").removeSuffix("}").trim()
        if (inner.any { it == '{' || it == '[' }) throw TestigoException("$parent tiene valores anidados en una línea: edítalo a mano.")
        val entries = splitTopLevel(inner).filter { it.isNotBlank() }.map { entry ->
            val colon = entry.indexOf(':')
            if (colon <= 0) throw TestigoException("No entiendo $parent en testigo.yaml: edítalo a mano.")
            entry.substring(0, colon).trim() to entry.substring(colon + 1).trim()
        }.toMutableList()
        val index = entries.indexOfFirst { it.first == key }
        if (index >= 0) entries[index] = key to yaml else entries += key to yaml
        return entries.joinToString(", ", prefix = "{ ", postfix = " }") { "${it.first}: ${it.second}" }
    }

    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var quote: Char? = null
        var start = 0
        text.forEachIndexed { i, c ->
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c == ',' -> {
                    parts += text.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += text.substring(start)
        return parts
    }
}

/** Escalares de YAML: sin comillas si se leen igual, entre comillas dobles si no (rutas con `:`, `#`, `true`, números). */
object YamlScalar {
    private val PLAIN = Regex("^[A-Za-z0-9_./~][A-Za-z0-9_./~+@() -]*$")
    private val RESERVED = Regex("^(true|false|yes|no|on|off|null|~|y|n)$", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("^[-+]?(\\d[\\d_]*(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?$")

    fun string(value: String): String {
        val plain = PLAIN.matches(value) && value == value.trim() && !RESERVED.matches(value) && !NUMBER.matches(value)
        if (plain) return value
        val escaped = buildString {
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\x%02x".format(c.code)) else append(c)
                }
            }
        }
        return "\"$escaped\""
    }
}

/** Rutas para guardar en testigo.yaml: relativas a su carpeta (así sirven en otra máquina con el mismo orden de repos). */
object ConfigPaths {
    private const val MAX_PARENTS = 3

    fun relative(dir: Path, target: Path): String {
        val base = dir.toAbsolutePath().normalize()
        val absolute = target.toAbsolutePath().normalize()
        val relative = runCatching { base.relativize(absolute) }.getOrNull() ?: return absolute.toString().replace('\\', '/')
        val parents = relative.takeWhile { it.toString() == ".." }.count()
        if (parents > MAX_PARENTS) return absolute.toString().replace('\\', '/')
        return relative.toString().replace('\\', '/').ifEmpty { "." }
    }
}
