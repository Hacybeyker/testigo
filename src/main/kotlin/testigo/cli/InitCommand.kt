package testigo.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import testigo.TestigoException
import testigo.ExitCode
import testigo.android.AndroidSdk
import testigo.artifact.Artifact
import testigo.artifact.ArtifactFormat
import testigo.artifact.ArtifactInfo
import testigo.artifact.ArtifactInspector
import testigo.artifact.Bundletool
import testigo.artifact.BuildFinder
import testigo.artifact.ChuckerDetector
import testigo.config.ConfigLoader
import testigo.config.ConfigPaths
import testigo.config.NetworkMode
import testigo.maestro.MaestroCli
import testigo.process.ProcessCommandRunner
import testigo.scaffold.Scaffold
import testigo.scaffold.ScaffoldStatus
import testigo.scaffold.SuiteAnswers
import testigo.setup.MaestroRelease
import testigo.util.displayFrom
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class InitCommand : CliktCommand(name = "init") {

    override fun help(context: Context): String =
        "Crea la suite en esta carpeta: testigo.yaml, flows/ con un flow de demo, .env.testigo y el .gitignore.\n\n" +
            "testigo.yaml queda con la configuración por defecto y la app que elijas (o pendiente: testigo run la pide). .env.testigo es " +
            "para los usuarios de prueba. Nunca pisa un archivo que ya existe. Después, testigo doctor."

    private val dir by option("--dir", help = "Carpeta de la suite. Por defecto, la actual; se crea si no existe.")
        .path()
    private val app by option("--app", help = "APK o AAB de la app. Sin esto, lo pregunta; sin terminal, queda pendiente.")
        .path()
    private val network by option("--network", help = "Si la app trae Chucker: chucker o none. Por defecto se detecta en el build.")
        .enum<NetworkMode> { it.cliName }

    override fun run() {
        val cwd = Path.of("").toAbsolutePath().normalize()
        val target = (dir?.let { cwd.resolve(it) } ?: cwd).toAbsolutePath().normalize()
        val info = terminal.terminalInfo
        val asker = if (info.inputInteractive && info.outputInteractive) MordantAsker(terminal) else null
        val runner = ProcessCommandRunner()
        val code = InitFlow(
            dir = target,
            cwd = cwd,
            asker = asker,
            inspect = { path ->
                val format = ArtifactFormat.of(path.name) ?: return@InitFlow null
                runCatching {
                    ArtifactInspector(runner, { AndroidSdk.locate().aapt2 }, Bundletool(runner)).inspect(Artifact(path, format))
                }.getOrNull()
            },
            maestroVersion = { runCatching { MaestroCli.locate().version() }.getOrNull() },
            configuredArtifact = { file ->
                runCatching {
                    val project = ConfigLoader.load(file, cwd)
                    project.config.app.artifact?.let { project.dir.resolve(it).normalize() }
                }.getOrNull()
            },
            out = { echo(it) },
        ).run(app?.let { cwd.resolve(it).normalize() }, network)
        if (code != ExitCode.OK) throw ProgramResult(code.code)
    }
}

/** El recorrido de `testigo init`, sin terminal real para poder probarlo. */
class InitFlow(
    private val dir: Path,
    private val cwd: Path,
    private val asker: Asker?,
    /** Package y versión del build; null si no se pudo leer (sin aapt2, por ejemplo). */
    private val inspect: (Path) -> ArtifactInfo?,
    private val maestroVersion: () -> String?,
    /** El `app.artifact` de un testigo.yaml que ya existe, resuelto. */
    private val configuredArtifact: (Path) -> Path? = { null },
    private val chucker: (Path) -> Boolean? = { ChuckerDetector.detect(it) },
    private val builds: (Path) -> List<Path> = { BuildFinder.near(it) },
    private val out: (String) -> Unit,
) {
    private class Cancelled : RuntimeException()

    fun run(app: Path?, network: NetworkMode?): ExitCode {
        out("testigo init · crea la suite en ${dir.displayFrom(cwd).let { if (it == ".") "esta carpeta" else it }}")
        out("")
        if (app != null && (!app.isRegularFile() || ArtifactFormat.of(app.name) == null)) {
            out("Error: --app tiene que ser un .apk o un .aab que exista: ${app.displayFrom(cwd)}")
            return ExitCode.ENVIRONMENT
        }
        return try {
            create(app, network)
            ExitCode.OK
        } catch (_: Cancelled) {
            out("Cancelado: no creé nada.")
            ExitCode.CANCELLED
        } catch (e: TestigoException) {
            out("Error: ${e.message}")
            e.exitCode
        }
    }

    private fun create(appFlag: Path?, network: NetworkMode?) {
        val config = dir.resolve(Scaffold.CONFIG)
        val existing = config.isRegularFile()
        val app = when {
            appFlag != null -> appFlag
            existing -> configuredArtifact(config)?.takeIf { it.isRegularFile() }
            else -> askApp()
        }
        val info = app?.let(inspect)
        when {
            existing -> Unit
            app == null -> asker?.answered("App", "pendiente: testigo run la pide")
            info != null -> asker?.answered("App", "${ConfigPaths.relative(cwd, app)} · ${info.label}")
            else -> asker?.answered("App", "${ConfigPaths.relative(cwd, app)} · no pude leer su package (¿falta aapt2? testigo setup lo instala)")
        }
        val packageName = info?.packageName ?: if (Scaffold.hasFlows(dir)) null else askPackage()
        val withChucker = if (existing) {
            false
        } else {
            network?.let { it == NetworkMode.CHUCKER } ?: detectChucker(app)
        }
        val answers = SuiteAnswers(
            artifact = app?.takeIf { !existing }?.let { ConfigPaths.relative(dir, it) },
            packageName = packageName,
            chucker = withChucker,
            maestroVersion = maestroVersion()?.takeIf { MaestroRelease.isVersion(it) } ?: MaestroRelease.DEFAULT_VERSION,
        )
        dir.createDirectories()
        val results = Scaffold.write(dir, answers)
        if (asker != null) out("")
        results.forEach { result ->
            val name = dir.relativize(result.path).toString().replace('\\', '/').let { if (result.path.toFile().isDirectory) "$it/" else it }
            val symbol = if (result.status == ScaffoldStatus.KEPT) "–" else "✔"
            out("  $symbol $name" + result.note?.let { "   ($it)" }.orEmpty())
        }
        out("")
        out("Siguiente: testigo doctor" + if (dir != cwd) " (desde ${dir.displayFrom(cwd)})" else "")
        if (!existing && app == null) out("  · Falta la app: testigo run te la pide y te ofrece guardarla en testigo.yaml, o escríbela en app.artifact.")
        val demo = results.any { it.path.endsWith(Scaffold.DEMO_FLOW) && it.status == ScaffoldStatus.CREATED }
        if (demo && packageName == null) {
            out("  · El flow de demo usa appId ${Scaffold.PLACEHOLDER_PACKAGE}: cámbialo por el package de tu app en ${Scaffold.DEMO_FLOW}.")
        }
        if (demo) out("  · Luego, testigo run ejecuta el flow de demo: abre la app y espera a que cargue.")
    }

    /** Los builds de Gradle cercanos, una ruta escrita o nada (pendiente). Sin terminal, pendiente. */
    private fun askApp(): Path? {
        val asker = asker ?: return null
        val found = builds(dir)
        if (found.isEmpty()) {
            return typed(asker.text("Ruta del APK o AAB de la app (Enter: después, testigo run la pide)"))
        }
        val names = found.map { ConfigPaths.relative(cwd, it) }
        val width = names.maxOf { it.length } + 3
        val choices = found.mapIndexed { i, path -> Choice(names[i].padEnd(width) + BuildFinder.describe(path)) } +
            Choice("Otra ruta: la escribo") + Choice("Todavía no tengo el build: testigo run la pide")
        val index = asker.one("App que vas a probar (APK o AAB) · builds encontrados cerca de esta carpeta", choices) ?: throw Cancelled()
        return when (index) {
            in found.indices -> found[index]
            found.size -> typed(asker.text("Ruta del APK o AAB"))
            else -> null
        }
    }

    private fun typed(text: String?): Path? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val path = cwd.resolve(value.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home"))).normalize()
        if (!path.isRegularFile() || ArtifactFormat.of(path.name) == null) {
            out("! No encontré un .apk o .aab en ${path.displayFrom(cwd)}: lo dejo pendiente.")
            return null
        }
        return path
    }

    private fun askPackage(): String? {
        val asker = asker ?: return null
        val text = asker.text("Package de la app para el flow de demo, por ejemplo com.miempresa.app (Enter: después)")?.trim()
        return text?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) }
            ?: text?.takeIf { it.isNotEmpty() }?.let { out("! \"$it\" no parece un package: lo dejo pendiente."); null }
    }

    /** En el build, si hay uno; si no, se pregunta; sin terminal, sin Chucker. */
    private fun detectChucker(app: Path?): Boolean {
        val detected = app?.let(chucker)
        if (detected != null) {
            asker?.answered("Red", if (detected) "con Chucker: lo encontré en el build" else "sin captura: el build no trae Chucker")
            return detected
        }
        val asker = asker ?: return false
        val index = asker.one(
            "¿La app trae Chucker para capturar la red?",
            listOf(Choice("Sí: network.capture: chucker"), Choice("No o no sé: network.capture: none (se cambia después)")),
        ) ?: throw Cancelled()
        asker.answered("Red", if (index == 0) "con Chucker" else "sin captura")
        return index == 0
    }
}
