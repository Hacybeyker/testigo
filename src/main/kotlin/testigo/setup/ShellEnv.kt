package testigo.setup

import testigo.util.tilde
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Las terminales que `testigo setup` sabe configurar. */
enum class ShellKind(val label: String) { ZSH("zsh"), BASH("bash"), FISH("fish"), SH("sh") }

/**
 * Dónde queda el entorno de testigo para una terminal. Para zsh, bash y sh, en [envFile] (`~/.testigo/env.sh`) y una línea
 * que lo carga en [profile] (`~/.zshrc`). Para fish, [envFile] es `~/.config/fish/conf.d/testigo.fish`, que fish carga
 * solo: [profile] es null. Todo son archivos del usuario: no hace falta root.
 */
data class ShellProfile(val kind: ShellKind, val profile: Path?, val envFile: Path)

/** Una variable o una carpeta del PATH que el entorno de testigo define, con lo que se muestra al usuario. */
sealed class EnvEntry(val label: String) {
    class Variable(val name: String, val value: String, label: String = name) : EnvEntry(label)

    /** [tag] identifica la carpeta en el archivo generado, para mantenerla al regenerarlo. */
    class PathDir(val dir: Path, val tag: String, label: String) : EnvEntry(label)
}

/**
 * Genera el entorno de testigo y la línea que lo carga. El bloque del perfil va entre marcas para poder actualizarlo o
 * quitarlo sin tocar nada más del archivo.
 */
object ShellEnv {
    const val BEGIN = "# >>> testigo >>>"
    const val END = "# <<< testigo <<<"
    const val BACKUP_SUFFIX = ".testigo-backup"

    /** Las carpetas del PATH que maneja testigo, como se marcan en el archivo generado. */
    const val TAG_TESTIGO = "testigo"
    const val TAG_MAESTRO = "maestro"
    const val TAG_ADB = "adb"

    fun detect(env: Map<String, String>, shellHome: Path, state: Path, osName: String): ShellProfile {
        val shell = env["SHELL"].orEmpty().substringAfterLast('/')
        val mac = osName.startsWith("Mac", ignoreCase = true)
        val envSh = state.resolve("env.sh")
        return when (shell) {
            "zsh" -> ShellProfile(ShellKind.ZSH, Path.of(env["ZDOTDIR"]?.takeIf { it.isNotBlank() } ?: shellHome.toString()).resolve(".zshrc"), envSh)
            "bash" -> ShellProfile(ShellKind.BASH, bashProfile(shellHome, mac), envSh)
            "fish" -> {
                val config = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: shellHome.resolve(".config")
                ShellProfile(ShellKind.FISH, null, config.resolve("fish/conf.d/testigo.fish"))
            }
            else -> ShellProfile(ShellKind.SH, shellHome.resolve(".profile"), envSh)
        }
    }

    /**
     * En macOS cada terminal nueva es un bash de login: lee `~/.bash_profile`, o si no existe `~/.bash_login`, o si no
     * `~/.profile`. Crear `~/.bash_profile` cuando el usuario usa `~/.profile` lo dejaría de leer: se usa el que ya existe.
     * En Linux, las terminales leen `~/.bashrc`.
     */
    private fun bashProfile(home: Path, mac: Boolean): Path {
        if (!mac) return home.resolve(".bashrc")
        return listOf(".bash_profile", ".bash_login", ".profile").map { home.resolve(it) }.firstOrNull { it.isRegularFile() }
            ?: home.resolve(".bash_profile")
    }

    /** El archivo de entorno para [kind], con [entries] en orden: las carpetas, de la última a la primera en el PATH. */
    fun render(kind: ShellKind, entries: List<EnvEntry>, shellHome: Path, loadedFrom: Path?): String = buildString {
        appendLine("# Entorno de testigo. Lo genera `testigo setup`: no lo edites a mano, vuelve a ejecutar testigo setup para cambiarlo.")
        appendLine("# Solo variables de tu usuario, sin root." + (loadedFrom?.let { " Lo carga ${it.tilde(shellHome)}." }.orEmpty()))
        val variables = entries.filterIsInstance<EnvEntry.Variable>()
        val dirs = entries.filterIsInstance<EnvEntry.PathDir>()
        val maestro = variables.filter { it.name.startsWith("MAESTRO_") }
        val others = variables - maestro.toSet()
        if (kind == ShellKind.FISH) {
            others.forEach { appendLine("set -gx ${it.name} ${fishQuote(it.value, shellHome)}") }
            if (maestro.isNotEmpty()) appendLine(MAESTRO_COMMENT)
            maestro.forEach { appendLine("set -gx ${it.name} ${fishQuote(it.value, shellHome)}") }
            dirs.forEach { dir ->
                val quoted = fishQuote(dir.dir.toString(), shellHome)
                appendLine("contains -- $quoted \$PATH; or set -gx PATH $quoted \$PATH    # ${dir.tag}")
            }
        } else {
            others.forEach { appendLine("export ${it.name}=${shQuote(it.value, shellHome)}") }
            if (maestro.isNotEmpty()) appendLine(MAESTRO_COMMENT)
            maestro.forEach { appendLine("export ${it.name}=${shQuote(it.value, shellHome)}") }
            if (dirs.isNotEmpty()) {
                appendLine("testigo_path_add () { case \":\$PATH:\" in *\":\$1:\"*) ;; *) PATH=\"\$1:\$PATH\" ;; esac ; }")
                dirs.forEach { appendLine("testigo_path_add ${shQuote(it.dir.toString(), shellHome)}    # ${it.tag}") }
                appendLine("unset -f testigo_path_add")
                appendLine("export PATH")
            }
        }
    }

    /** Las líneas del perfil que cargan [envFile]. */
    fun block(envFile: Path, shellHome: Path): String {
        val path = shQuote(envFile.toString(), shellHome)
        return "$BEGIN\n" +
            "# Lo agregó `testigo setup`: carga el entorno de testigo (Java, Maestro, Android SDK y el comando testigo). Sin root.\n" +
            "[ -f $path ] && . $path\n" +
            END
    }

    /** [existing] con [block] en lugar del bloque anterior o, si no había, al final. */
    fun upsert(existing: String?, block: String): String {
        val text = existing.orEmpty()
        val start = text.indexOf(BEGIN)
        val end = text.indexOf(END, startIndex = maxOf(start, 0))
        if (start >= 0 && end > start) {
            return text.substring(0, start) + block + text.substring(end + END.length)
        }
        val separator = when {
            text.isEmpty() -> ""
            text.endsWith("\n\n") -> ""
            text.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        return text + separator + block + "\n"
    }

    fun hasBlock(text: String?): Boolean = text != null && BEGIN in text && END in text

    /** Lo que maneja un archivo de entorno ya generado: los nombres de variables y las marcas de carpetas. */
    fun managed(text: String?): Set<String> {
        if (text == null) return emptySet()
        val names = Regex("(?m)^(?:export|set -gx) ([A-Z_][A-Z0-9_]*)[= ]").findAll(text).map { it.groupValues[1] }
        val tags = Regex("(?m)# (${TAG_TESTIGO}|${TAG_MAESTRO}|${TAG_ADB})$").findAll(text).map { "PATH:" + it.groupValues[1] }
        return (names + tags).toSet()
    }

    /** Entre comillas dobles para sh, con `$HOME` en lugar de la carpeta del usuario para que se lea mejor. */
    internal fun shQuote(value: String, shellHome: Path): String {
        val home = shellHome.toString().trimEnd('/')
        val (prefix, rest) = if (home.isNotEmpty() && (value == home || value.startsWith("$home/"))) "\$HOME" to value.removePrefix(home) else "" to value
        val escaped = rest.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")
        return "\"$prefix$escaped\""
    }

    private fun fishQuote(value: String, shellHome: Path): String {
        val home = shellHome.toString().trimEnd('/')
        val (prefix, rest) = if (home.isNotEmpty() && (value == home || value.startsWith("$home/"))) "\$HOME" to value.removePrefix(home) else "" to value
        val escaped = rest.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        return "\"$prefix$escaped\""
    }

    /** Ruta para mostrar: `~/.zshrc`. */
    fun display(path: Path, shellHome: Path): String = path.tilde(shellHome)

    private const val MAESTRO_COMMENT = "# Maestro sin analytics ni consultas a sus servidores, también cuando lo usas fuera de testigo."

    /** Nombre corto del perfil, para mensajes: `.zshrc`. */
    fun shortName(profile: Path): String = profile.name
}
