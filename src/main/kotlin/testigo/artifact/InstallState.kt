package testigo.artifact

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Qué build instaló el runner en cada dispositivo: hash del artefacto y `lastUpdateTime` que reportó el dispositivo
 * justo después. Si alguien instala otra cosa a mano, `lastUpdateTime` cambia y el runner vuelve a instalar.
 */
class InstallState(private val file: Path) {

    data class Record(val sha256: String, val lastUpdateTime: String)

    fun get(serial: String, packageName: String): Record? {
        val value = load().getProperty(key(serial, packageName)) ?: return null
        val sha256 = value.substringBefore('|')
        return Record(sha256, value.substringAfter('|', ""))
    }

    fun put(serial: String, packageName: String, record: Record) {
        val properties = load()
        properties.setProperty(key(serial, packageName), "${record.sha256}|${record.lastUpdateTime}")
        file.parent.createDirectories()
        val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        tmp.outputStream().use { properties.store(it, "Instalaciones hechas por testigo. Se puede borrar sin problema.") }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun load(): Properties = Properties().also { properties ->
        if (file.exists()) file.inputStream().use { properties.load(it) }
    }

    private fun key(serial: String, packageName: String) = "$serial/$packageName"
}
