package testigo.scaffold

import testigo.config.YamlScalar
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Lo que `testigo init` sabe de la app para escribir la suite. null: pendiente. */
data class SuiteAnswers(
    /** Relativa a la carpeta de la suite. */
    val artifact: String?,
    val packageName: String?,
    val chucker: Boolean,
    val maestroVersion: String,
)

enum class ScaffoldStatus { CREATED, UPDATED, KEPT }

data class ScaffoldResult(val path: Path, val status: ScaffoldStatus, val note: String? = null)

/**
 * Los archivos de una suite nueva. Nunca pisa uno que ya existe: `testigo init` se puede volver a ejecutar para completar
 * lo que falte.
 */
object Scaffold {
    const val CONFIG = "testigo.yaml"
    const val ENV_FILE = ".env.testigo"
    const val FLOWS = "flows/"
    const val DEMO_FLOW = "flows/demo/abrir_app.yaml"
    const val PLACEHOLDER_PACKAGE = "com.example.app"
    private const val COMMENT_COLUMN = 34

    fun write(dir: Path, answers: SuiteAnswers): List<ScaffoldResult> = buildList {
        dir.createDirectories()
        val config = dir.resolve(CONFIG)
        if (config.exists()) {
            add(ScaffoldResult(config, ScaffoldStatus.KEPT, "ya existía: no lo toqué"))
        } else {
            config.writeText(config(answers))
            add(ScaffoldResult(config, ScaffoldStatus.CREATED))
        }

        val flows = dir.resolve(FLOWS)
        if (hasFlows(dir)) {
            add(ScaffoldResult(flows, ScaffoldStatus.KEPT, "ya tiene flows: no agregué el de demo"))
        } else {
            val demo = dir.resolve(DEMO_FLOW)
            demo.parent.createDirectories()
            demo.writeText(demoFlow(answers.packageName))
            add(ScaffoldResult(demo, ScaffoldStatus.CREATED))
        }

        val env = dir.resolve(ENV_FILE)
        if (env.exists()) {
            add(ScaffoldResult(env, ScaffoldStatus.KEPT, "ya existía: no lo toqué"))
        } else {
            env.writeText(ENV_TEXT)
            add(ScaffoldResult(env, ScaffoldStatus.CREATED))
        }

        val gitignore = dir.resolve(".gitignore")
        val existing = if (gitignore.isRegularFile()) gitignore.readText() else null
        val updated = gitignore(existing)
        when {
            updated == null -> add(ScaffoldResult(gitignore, ScaffoldStatus.KEPT, "ya ignora $ENV_FILE y evidence/"))
            existing == null -> {
                gitignore.writeText(updated)
                add(ScaffoldResult(gitignore, ScaffoldStatus.CREATED))
            }
            else -> {
                gitignore.writeText(updated)
                add(ScaffoldResult(gitignore, ScaffoldStatus.UPDATED, "agregué lo que faltaba"))
            }
        }
    }

    /** Si `flows/` ya tiene algún flow: entonces no hace falta el de demo. */
    fun hasFlows(dir: Path): Boolean {
        val flows = dir.resolve(FLOWS)
        return flows.isDirectory() && Files.walk(flows).use { paths -> paths.anyMatch { it.isRegularFile() && it.name.endsWith(".yaml") } }
    }

    fun config(answers: SuiteAnswers): String = buildString {
        appendLine("# La suite E2E de esta carpeta. La creó `testigo init`: cámbiala cuando quieras, testigo conserva tus comentarios.")
        appendLine("# Las rutas son relativas a este archivo. Todas las claves están en docs/USO.md, en la carpeta de testigo.")
        appendLine("version: 1")
        appendLine()
        appendLine("app:")
        if (answers.artifact != null) {
            appendLine(line("  artifact: ${YamlScalar.string(answers.artifact)}", "el APK o AAB que se prueba"))
        } else {
            appendLine(line("  artifact:", "pendiente: la ruta de tu APK o AAB (testigo run la pide y la puede guardar)"))
        }
        appendLine(line("  package: auto", "auto: se lee del build"))
        appendLine()
        appendLine(line("flows: $FLOWS", "la carpeta de los flows de Maestro"))
        appendLine()
        appendLine("maestro:")
        appendLine(line("  version: \"${answers.maestroVersion}\"", "la del equipo: testigo doctor avisa si la instalada es otra"))
        appendLine()
        appendLine("defaults:")
        appendLine(line("  profile: dev", "qué capturar sin flags: smoke, dev o evidencia"))
        appendLine()
        appendLine("network:")
        if (answers.chucker) {
            appendLine(line("  capture: chucker", "la red sale de Chucker; none si la app no lo trae"))
        } else {
            appendLine(line("  capture: none", "chucker si la app trae Chucker"))
        }
        appendLine(line("  format: txt", "txt, har o ambos"))
        appendLine(line("  hosts: []", "tu backend de QA, por ejemplo [api.qa.miempresa.com]"))
        appendLine()
        appendLine("video:")
        appendLine(line("  bitrate_mbps: 4", "de 4 a 8: más alto, más nítido y más pesado"))
        appendLine()
        appendLine("redact:")
        appendLine(line("  enabled: false", "true: enmascara tokens y datos personales y corre el leak scan"))
        appendLine()
        appendLine("evidence:")
        appendLine(line("  dir: evidence/", "no se sube al repo: testigo le crea su propio .gitignore"))
    }

    fun demoFlow(packageName: String?): String = buildString {
        appendLine("# Flow de demo de `testigo init`: abre la app y espera a que la pantalla quede quieta. Pasa con cualquier app.")
        appendLine("# Sirve para comprobar que todo funciona; después cámbialo o bórralo y escribe los tuyos (docs/GUIA.md, sección 7).")
        if (packageName != null) {
            appendLine("appId: $packageName")
        } else {
            appendLine("appId: $PLACEHOLDER_PACKAGE        # cámbialo por el package de tu app (su applicationId)")
        }
        appendLine("name: abrir_app")
        appendLine("tags: [demo, smoke]")
        appendLine("---")
        appendLine("- launchApp")
        appendLine("- waitForAnimationToEnd")
    }

    const val ENV_TEXT = "# Variables para los flows de esta suite: llegan como \${NOMBRE}, por ejemplo \${USER}.\n" +
        "# Solo usuarios de prueba de QA, nunca datos reales. Este archivo no se sube al repo (.gitignore)\n" +
        "# y testigo nunca imprime sus valores.\n" +
        "# USER=qa_usuario_01\n" +
        "# PASSWORD=ClaveDePrueba123\n"

    /** El `.gitignore` con `.env.testigo` y `evidence/`; null si ya los tiene. */
    fun gitignore(existing: String?): String? {
        val lines = existing.orEmpty().lines().map { it.trim().removePrefix("/").removeSuffix("/") }
        val missing = listOf(ENV_FILE, "evidence/").filter { it.removeSuffix("/") !in lines }
        if (missing.isEmpty()) return null
        val header = "# testigo: los usuarios de prueba y la evidencia no se suben al repo."
        val block = (listOf(header) + missing).joinToString("\n", postfix = "\n")
        val text = existing.orEmpty()
        return when {
            text.isEmpty() -> block
            text.endsWith("\n") -> "$text\n$block"
            else -> "$text\n\n$block"
        }
    }

    private fun line(text: String, comment: String): String = text.padEnd(maxOf(COMMENT_COLUMN, text.length + 3)) + "# $comment"
}
