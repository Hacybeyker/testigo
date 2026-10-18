package testigo.util

import java.nio.file.Path

/**
 * Ruta para mostrar al usuario: relativa a [cwd] si está dentro de ella; si no, absoluta.
 * Así la salida es corta y el comando equivalente se puede copiar tal cual.
 */
fun Path.displayFrom(cwd: Path): String {
    val path = toAbsolutePath().normalize()
    val base = cwd.toAbsolutePath().normalize()
    return when {
        path == base -> "."
        path.startsWith(base) -> base.relativize(path).toString()
        else -> path.toString()
    }
}
