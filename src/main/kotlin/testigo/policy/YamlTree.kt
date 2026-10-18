package testigo.policy

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode

/** Árbol mínimo de un documento YAML, con la línea (desde 1) de cada nodo. Separa el lint de kaml. */
internal sealed interface TreeNode {
    val line: Int
}

internal data class TreeMap(val entries: List<Pair<TreeScalar, TreeNode>>, override val line: Int) : TreeNode

internal data class TreeList(val items: List<TreeNode>, override val line: Int) : TreeNode

internal data class TreeScalar(val text: String, override val line: Int) : TreeNode

internal data class TreeNull(override val line: Int) : TreeNode

internal class YamlSyntaxException(message: String, val line: Int) : Exception(message)

internal fun interface YamlTreeParser {
    /** Lee un solo documento YAML. Lanza [YamlSyntaxException] si no es válido. */
    fun parse(text: String): TreeNode
}

internal class KamlTreeParser : YamlTreeParser {
    private val yaml = Yaml(
        configuration = YamlConfiguration(strictMode = false, anchorsAndAliases = AnchorsAndAliases.Permitted()),
    )

    override fun parse(text: String): TreeNode = try {
        convert(yaml.parseToYamlNode(text))
    } catch (e: YamlException) {
        throw YamlSyntaxException(e.message, e.line)
    } catch (e: RuntimeException) {
        throw YamlSyntaxException(e.message ?: "YAML inválido", 1)
    }

    private fun convert(node: YamlNode): TreeNode = when (node) {
        is YamlMap -> TreeMap(
            node.entries.map { (key, value) -> TreeScalar(key.content, key.location.line) to convert(value) },
            node.location.line,
        )
        is YamlList -> TreeList(node.items.map(::convert), node.location.line)
        is YamlScalar -> TreeScalar(node.content, node.location.line)
        is YamlNull -> TreeNull(node.location.line)
        is YamlTaggedNode -> convert(node.innerNode)
    }
}

/** Separa un archivo en sus documentos YAML (`---`). Un flow de Maestro tiene dos: header y comandos. */
internal object YamlDocuments {
    data class Document(val firstLine: Int, val text: String)

    fun split(text: String): List<Document> {
        val lines = text.lines()
        val documents = mutableListOf<Document>()
        var start = 0
        fun close(end: Int) {
            val chunk = lines.subList(start, end)
            if (chunk.any { it.isNotBlank() && !it.trimStart().startsWith("#") }) {
                documents += Document(start + 1, chunk.joinToString("\n"))
            }
        }
        lines.forEachIndexed { index, line ->
            if (line.trimEnd() == "---") {
                close(index)
                start = index + 1
            }
        }
        close(lines.size)
        return documents
    }
}
