package testigo.policy

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Regla de la política local-only que incumple un flow. */
enum class PolicyRule(val label: String) {
    AI_COMMAND("comando de IA"),
    EXTERNAL_NETWORK("red externa"),
    CREDENTIAL("credencial en el flow"),
    UNVERIFIABLE("no verificable"),
}

/** Una violación, con archivo y línea. El mensaje nunca incluye el valor de una credencial. */
data class PolicyViolation(val file: Path, val line: Int?, val rule: PolicyRule, val message: String)

/**
 * Un archivo que Maestro no acepta: le falta la configuración (`appId` y `---`), que exige en cada archivo, también en los
 * subflows. No es de la política: la corrida termina con 2, no con 3. [fix] dice qué hacer y [snippet], qué escribir.
 */
data class FlowProblem(val file: Path, val line: Int?, val message: String, val fix: String, val snippet: List<String>)

data class LintResult(
    val checkedFiles: List<Path>,
    val violations: List<PolicyViolation>,
    val problems: List<FlowProblem> = emptyList(),
) {
    /** Cumple la política local-only. Los [problems] se revisan aparte. */
    val passed: Boolean get() = violations.isEmpty()
}

/** Resultado de la política para una corrida: el lint y las variables del shell que el runner quitará. */
data class PolicyReport(val lint: LintResult, val removedEnv: List<String>)

/**
 * Lint de la política local-only. Revisa cada flow, sus subflows (`runFlow`), sus scripts (`runScript`)
 * y cada expresión `${…}`, y bloquea:
 * - los comandos de IA (`assertWithAI`, `assertNoDefectsWithAI`, `extractTextWithAI` y cualquier `*WithAI`);
 * - `http` en JavaScript hacia un host que no sea localhost;
 * - credenciales escritas en el YAML.
 * Lo que no puede verificar (una ruta dinámica, un archivo que no existe, un script que no entiende) también es violación.
 *
 * Revisa además que cada archivo YAML, subflows incluidos, tenga la forma que Maestro exige: la configuración con `appId`,
 * `---` y los comandos. Sin eso Maestro falla con "Config Section Required" antes de ejecutar nada.
 */
class FlowLinter internal constructor(private val parser: YamlTreeParser) {

    constructor() : this(KamlTreeParser())

    /**
     * [appId] es el que se sugiere escribir en un archivo sin configuración, si no hay uno mejor: para un subflow, se usa el
     * del flow que lo llama; si no, este; si no, el primero que declare algún flow.
     */
    fun lint(flows: List<Path>, appId: String? = null): LintResult {
        val run = Run()
        flows.forEach { run.yamlFile(it.toAbsolutePath().normalize(), parentAppId = null) }
        val suggested = appId ?: run.declaredAppIds.firstOrNull() ?: PLACEHOLDER_APP_ID
        return LintResult(run.checked.toList(), run.violations.distinct(), run.shapes.map { it.problem(suggested) })
    }

    private enum class Shape { EMPTY, NO_CONFIG, NO_APP_ID, NO_SEPARATOR, NO_COMMANDS }

    private data class ShapeIssue(val file: Path, val line: Int?, val shape: Shape, val appId: String?) {
        fun problem(fallback: String): FlowProblem {
            val id = appId ?: fallback
            return when (shape) {
                Shape.EMPTY -> FlowProblem(
                    file, null, "está vacío; Maestro necesita la configuración (appId y ---) y los comandos", "Escribe, por ejemplo:",
                    listOf("appId: $id", "---", "- launchApp"),
                )
                Shape.NO_CONFIG -> FlowProblem(
                    file, null, "no empieza con la configuración (appId y ---), que Maestro exige en cada archivo, también en los subflows",
                    "Agrega al inicio:", listOf("appId: $id", "---"),
                )
                Shape.NO_APP_ID -> FlowProblem(
                    file, line, "la configuración no tiene appId, que Maestro exige en cada archivo, también en los subflows",
                    "Agrega en la configuración, antes del ---:", listOf("appId: $id"),
                )
                Shape.NO_SEPARATOR -> FlowProblem(
                    file, null, "tiene la configuración, pero le falta el --- y los comandos",
                    "Agrega debajo de la configuración una línea con --- y, después, los comandos.", emptyList(),
                )
                Shape.NO_COMMANDS -> FlowProblem(
                    file, null, "no tiene comandos después del ---", "Agrega los comandos debajo del ---.", emptyList(),
                )
            }
        }
    }

    private inner class Run {
        val checked = linkedSetOf<Path>()
        val violations = mutableListOf<PolicyViolation>()
        val shapes = mutableListOf<ShapeIssue>()
        val declaredAppIds = linkedSetOf<String>()

        /** [parentAppId]: el appId del flow que llama a este archivo con runFlow, para sugerirlo si a este le falta. */
        fun yamlFile(file: Path, parentAppId: String?) {
            val text = read(file) ?: return
            val documents = YamlDocuments.split(text)
            if (documents.isEmpty()) shapes += ShapeIssue(file, null, Shape.EMPTY, parentAppId)
            var appId = parentAppId
            documents.forEachIndexed { index, document ->
                val tree = try {
                    parser.parse(document.text)
                } catch (e: YamlSyntaxException) {
                    addViolation(file, document.firstLine + e.line - 1, PolicyRule.UNVERIFIABLE, "YAML inválido, no se puede revisar")
                    return@forEachIndexed
                }
                if (index == 0) {
                    val declared = (tree as? TreeMap)?.let(::declaredAppId)
                    declared?.let { declaredAppIds += it }
                    appId = declared ?: parentAppId
                    shape(tree, documents.size, text.lines().any { it.trimEnd() == "---" }, document.firstLine - 1)?.let { shapes += ShapeIssue(file, it.second, it.first, parentAppId) }
                }
                Walker(file, document.firstLine - 1, appId).walk(tree)
            }
        }

        /** Lo que le falta al archivo según su primer documento, con la línea donde mirar; null si tiene la forma de Maestro. */
        private fun shape(first: TreeNode, documents: Int, separated: Boolean, lineOffset: Int): Pair<Shape, Int?>? = when {
            first !is TreeMap -> Shape.NO_CONFIG to null
            declaredAppId(first) == null && first.entries.none { it.first.text == "url" } -> Shape.NO_APP_ID to lineOffset + (first.entries.firstOrNull()?.first ?: first).line
            !separated -> Shape.NO_SEPARATOR to null
            documents < 2 -> Shape.NO_COMMANDS to null
            else -> null
        }

        private fun declaredAppId(config: TreeMap): String? =
            (config.entries.firstOrNull { it.first.text == "appId" }?.second as? TreeScalar)?.text?.takeIf { it.isNotBlank() }

        fun jsFile(file: Path) {
            val code = read(file) ?: return
            JsPolicy.check(code).forEach { addViolation(file, it.line, it.rule, it.message) }
        }

        /** Lee el archivo la primera vez; null si ya se revisó o no se pudo leer. */
        private fun read(file: Path): String? {
            if (!checked.add(file)) return null
            return try {
                file.readText()
            } catch (e: IOException) {
                addViolation(file, null, PolicyRule.UNVERIFIABLE, "no se pudo leer el archivo")
                null
            }
        }

        fun addViolation(file: Path, line: Int?, rule: PolicyRule, message: String) {
            violations += PolicyViolation(file, line, rule, message)
        }

        private inner class Walker(private val file: Path, private val lineOffset: Int, private val appId: String?) {
            private val dir: Path = file.parent

            fun walk(node: TreeNode) {
                when (node) {
                    is TreeMap -> node.entries.forEach { (key, value) -> entry(key, value) }
                    is TreeList -> list(node)
                    is TreeScalar -> scalar(node)
                    is TreeNull -> Unit
                }
            }

            private fun entry(key: TreeScalar, value: TreeNode) {
                val name = key.text
                if (isAiCommand(name)) add(key, PolicyRule.AI_COMMAND, aiMessage(name))
                when (name) {
                    "runFlow" -> reference(value, name) { yamlFile(it, appId) }
                    "runScript" -> reference(value, name) { jsFile(it) }
                    "env" -> if (value is TreeMap) env(value)
                }
                walk(value)
            }

            /** Lista de comandos: además del recorrido normal, detecta texto fijo escrito en un campo de contraseña. */
            private fun list(node: TreeList) {
                var secretField: String? = null
                for (item in node.items) {
                    if (item is TreeScalar && isAiCommand(item.text)) add(item, PolicyRule.AI_COMMAND, aiMessage(item.text))
                    val command = (item as? TreeMap)?.entries?.singleOrNull()
                    val argument = command?.second
                    when (command?.first?.text) {
                        "tapOn" -> secretField = argument?.let(::secretSelector)
                        "inputText" -> {
                            val value = argument?.let(::inputTextValue)
                            if (secretField != null && value != null && isLiteral(value.text)) {
                                add(
                                    value,
                                    PolicyRule.CREDENTIAL,
                                    "inputText escribe un texto fijo en el campo `$secretField`; usa una variable de .env.testigo",
                                )
                            }
                            secretField = null
                        }
                    }
                    walk(item)
                }
            }

            private fun scalar(node: TreeScalar) {
                for (expression in JsExpressions.extract(node.text)) {
                    JsPolicy.check(expression).forEach { add(node, it.rule, it.message) }
                }
                if (TOKEN_PATTERNS.any { it.containsMatchIn(node.text) }) {
                    add(node, PolicyRule.CREDENTIAL, "parece un token o una clave escrita en el flow; usa una variable de .env.testigo")
                }
            }

            private fun env(map: TreeMap) {
                for ((key, value) in map.entries) {
                    if (value is TreeScalar && isSecretName(key.text) && isLiteral(value.text)) {
                        add(
                            value,
                            PolicyRule.CREDENTIAL,
                            "`${key.text}` tiene un valor escrito en el flow; usa \${${key.text}} y ponlo en .env.testigo",
                        )
                    }
                }
            }

            /** `runFlow`/`runScript` con archivo: lo revisa también. Los comandos en línea los cubre [walk]. */
            private fun reference(value: TreeNode, command: String, next: (Path) -> Unit) {
                val target = when (value) {
                    is TreeScalar -> value
                    is TreeMap -> value.entries.firstOrNull { it.first.text == "file" }?.second as? TreeScalar ?: return
                    else -> return
                }
                if (target.text.contains("\${")) {
                    add(target, PolicyRule.UNVERIFIABLE, "$command con una ruta dinámica (${target.text}), no se puede revisar")
                    return
                }
                val path = dir.resolve(target.text).normalize()
                if (!path.isRegularFile()) {
                    add(target, PolicyRule.UNVERIFIABLE, "$command apunta a un archivo que no existe: ${target.text}")
                    return
                }
                next(path)
            }

            private fun add(node: TreeNode, rule: PolicyRule, message: String) =
                addViolation(file, lineOffset + node.line, rule, message)
        }
    }

    internal companion object {
        /** Lo que se sugiere como appId cuando ningún flow declara uno. */
        const val PLACEHOLDER_APP_ID = "<applicationId de tu app>"

        private val SECRET_WORDS = setOf(
            "password", "passwd", "pass", "pwd", "secret", "token", "apikey", "pin", "otp", "cvv", "cvc",
            "clave", "contrasena", "contraseña",
        )
        private val SECRET_FRAGMENTS = listOf("password", "secret", "token", "apikey", "contrase")

        /** Texto de un selector que indica un campo de contraseña, PIN, token… */
        private val SECRET_FIELD =
            Regex("password|passwd|passcode|contrase|clave|secret|token|(^|[^a-z])(pass|pwd|pin|otp|cvv|cvc)([^a-z]|$)")
        private val SELECTOR_KEYS = setOf("id", "text", "label")

        private val TOKEN_PATTERNS = listOf(
            Regex("eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), // JWT
            Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        )

        fun isAiCommand(name: String): Boolean =
            name.length > "WithAI".length && name.endsWith("WithAI", ignoreCase = true) && name.all { it.isLetter() }

        fun isSecretName(name: String): Boolean {
            val words = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .lowercase()
                .split(Regex("[^a-z0-9ñ]+"))
                .filter { it.isNotEmpty() }
            val joined = words.joinToString("")
            return words.any { it in SECRET_WORDS } || SECRET_FRAGMENTS.any { it in joined }
        }

        fun isLiteral(text: String): Boolean = text.isNotBlank() && !text.contains("\${")

        private fun secretSelector(selector: TreeNode): String? {
            val texts = when (selector) {
                is TreeScalar -> listOf(selector.text)
                is TreeMap -> selector.entries.filter { it.first.text in SELECTOR_KEYS }.mapNotNull { (it.second as? TreeScalar)?.text }
                else -> emptyList()
            }
            return texts.firstOrNull { SECRET_FIELD.containsMatchIn(it.lowercase()) }
        }

        /** `inputText: "x"` o `inputText: { text: "x" }`. */
        private fun inputTextValue(value: TreeNode): TreeScalar? = when (value) {
            is TreeScalar -> value
            is TreeMap -> value.entries.firstOrNull { it.first.text == "text" }?.second as? TreeScalar
            else -> null
        }

        private fun aiMessage(name: String) ="`$name` envía capturas de pantalla a un servicio de IA"
    }
}
