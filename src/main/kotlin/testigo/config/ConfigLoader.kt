package testigo.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import testigo.TestigoException
import kotlinx.serialization.SerializationException
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object ConfigLoader {
    // Estricto: una clave desconocida (por ejemplo, un typo como "netwrok") es un error, no se ignora en silencio.
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

    /**
     * Carga [explicit] si se pasó `--config`; si no, busca testigo.yaml desde [cwd] hacia arriba.
     * Sin testigo.yaml se usan los valores por defecto y las rutas se resuelven desde [cwd].
     */
    fun load(explicit: Path?, cwd: Path): LoadedProject {
        val file = if (explicit != null) {
            cwd.resolve(explicit).toAbsolutePath().normalize().also {
                if (!it.isRegularFile()) throw TestigoException("No existe el archivo de configuración: $it")
            }
        } else {
            ProjectLocator.find(cwd) ?: return LoadedProject(ProjectConfig(), file = null, dir = cwd)
        }

        val config = parse(file.readText(), file.toString())
        ProjectValidator.validate(config, file.toString())
        return LoadedProject(config, file, file.parent)
    }

    fun parse(text: String, origin: String): ProjectConfig {
        if (text.isBlank()) return ProjectConfig()
        return try {
            yaml.decodeFromString(ProjectConfig.serializer(), text)
        } catch (e: YamlException) {
            throw TestigoException("$origin, línea ${e.line}, columna ${e.column}: ${e.message}")
        } catch (e: SerializationException) {
            throw TestigoException("$origin: ${e.message}")
        }
    }
}
