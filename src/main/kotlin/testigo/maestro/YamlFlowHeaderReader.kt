package testigo.maestro

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Lee el header de un flow con kaml. No es estricto: el header de Maestro tiene muchas claves
 * (onFlowStart, env, jsEngine...) que el runner no necesita.
 */
class YamlFlowHeaderReader : FlowHeaderReader {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    override fun read(path: Path): FlowHeader {
        val header = FlowHeaderText.extract(path.readText())
        if (header.isBlank()) return FlowHeader()
        return try {
            yaml.decodeFromString(FlowHeader.serializer(), header)
        } catch (e: YamlException) {
            throw IllegalArgumentException("línea ${e.line}: ${e.message}", e)
        }
    }
}
