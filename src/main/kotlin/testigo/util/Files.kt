package testigo.util

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink

/** SHA-256 de un archivo, en hexadecimal y minúsculas. */
fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Borra la carpeta y todo lo que tiene. No sigue enlaces simbólicos: borra el enlace, no lo que apunta. */
fun Path.deleteTree() {
    if (!exists() && !isSymbolicLink()) return
    if (isSymbolicLink() || !Files.isDirectory(this)) {
        Files.deleteIfExists(this)
        return
    }
    Files.walk(this).use { paths -> paths.sorted(Comparator.reverseOrder()).toList() }.forEach { Files.deleteIfExists(it) }
}

/** `~/Library/Android/sdk` si [this] está dentro de [home]; si no, la ruta absoluta. */
fun Path.tilde(home: Path): String {
    val absolute = toAbsolutePath().normalize()
    val base = home.toAbsolutePath().normalize()
    return when {
        absolute == base -> "~"
        absolute.startsWith(base) -> "~/" + base.relativize(absolute).toString().replace('\\', '/')
        else -> absolute.toString()
    }
}
