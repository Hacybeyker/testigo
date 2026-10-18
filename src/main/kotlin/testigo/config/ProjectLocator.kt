package testigo.config

import java.nio.file.Path
import kotlin.io.path.isRegularFile

object ProjectLocator {
    const val FILE_NAME = "testigo.yaml"

    /** Busca testigo.yaml desde [start] hacia arriba, como hace git con .git. */
    fun find(start: Path): Path? =
        generateSequence(start.toAbsolutePath().normalize()) { it.parent }
            .map { it.resolve(FILE_NAME) }
            .firstOrNull { it.isRegularFile() }
}
