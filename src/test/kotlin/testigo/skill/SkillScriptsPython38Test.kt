package testigo.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Los scripts de la skill `testigo-flows` prometen funcionar con Python 3.8. La máquina que corre las pruebas tiene otro Python
 * (la CI usa 3.12), así que no se pueden correr con un 3.8: se revisa su código. [CHECKER] parsea cada script con la gramática de
 * 3.8 (`ast.parse(..., feature_version=(3, 8))`) y recorre el árbol buscando `.is_relative_to`, que es de 3.9. Mirar el árbol y no
 * el texto hace que un comentario o un texto que la nombren no cuenten, y no depende de la versión de Python que lo corra.
 */
@DisabledOnOs(OS.WINDOWS)
class SkillScriptsPython38Test {

    @TempDir
    lateinit var tmp: Path

    private val scripts: List<Path> =
        Files.list(Path.of("skills/testigo-flows/scripts")).use { files -> files.filter { it.extension == "py" }.sorted().toList() }

    /** El código de salida y lo que encontró [CHECKER] en [files]. */
    private fun check(files: List<Path>): Pair<Int, String> {
        val python = Python.find()
        assumeTrue(python != null, "no hay python3 en esta máquina")
        val output = tmp.resolve("salida.txt")
        val process = ProcessBuilder(listOf(python!!, "-I", "-c", CHECKER) + files.map { it.toString() })
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "la revisión no terminó")
        return process.exitValue() to output.readText()
    }

    @Test
    fun `validar, pantalla y mapa parsean como Python 3_8 y no usan is_relative_to`() {
        assertEquals(listOf("mapa.py", "pantalla.py", "validar.py"), scripts.map { it.name }, "los scripts de la skill")

        val (code, out) = check(scripts)

        assertEquals(0, code, out)
    }

    @Test
    fun `la revision ve is_relative_to en el codigo, no en un comentario ni en un texto, y la sintaxis posterior a 3_8`() {
        val comment = tmp.resolve("comentario.py").also {
            it.writeText("from pathlib import Path\n# Path.is_relative_to es de Python 3.9\nTEXTO = 'is_relative_to'\nrel = Path('a').relative_to(Path('a'))\n")
        }
        val usage = tmp.resolve("uso.py").also {
            it.writeText("from pathlib import Path\n\nrel = 1\nif Path('a').is_relative_to(Path('b')):\n    rel = 2\n")
        }
        val syntax = tmp.resolve("sintaxis.py").also { it.writeText("match 1:\n    case 1:\n        pass\n") }

        assertEquals(0, check(listOf(comment)).first, "un comentario y un texto no usan el método")
        val (code, out) = check(listOf(usage))
        assertEquals(1, code, out)
        assertTrue("uso.py:4: usa .is_relative_to" in out, out)
        assertEquals(1, check(listOf(syntax)).first, "match es de Python 3.10")
    }

    private companion object {
        const val CHECKER = """
import ast, sys
problems = []
for path in sys.argv[1:]:
    with open(path, encoding='utf-8') as source:
        text = source.read()
    try:
        tree = ast.parse(text, path, feature_version=(3, 8))
    except SyntaxError as error:
        problems.append('%s:%s: sintaxis que Python 3.8 no acepta: %s' % (path, error.lineno, error.msg))
        continue
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and node.attr == 'is_relative_to':
            problems.append('%s:%s: usa .is_relative_to, que es de Python 3.9' % (path, node.lineno))
print('\n'.join(problems))
sys.exit(1 if problems else 0)
"""
    }
}
