package testigo.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import testigo.TestigoException
import testigo.ExitCode
import testigo.config.ConfigLoader
import testigo.doctor.CheckStatus
import testigo.doctor.DoctorSection
import testigo.maestro.MaestroCli
import testigo.setup.AndroidSdkInstaller
import testigo.setup.ClaudeSkill
import testigo.setup.CommandLineTools
import testigo.setup.CurlFetcher
import testigo.setup.DebugKeystoreCreator
import testigo.setup.EnvEntry
import testigo.setup.Fetcher
import testigo.setup.Machine
import testigo.setup.MachineState
import testigo.setup.MaestroInstaller
import testigo.setup.MaestroRelease
import testigo.setup.ProcessSetupShell
import testigo.setup.SetupAction
import testigo.setup.SetupPlan
import testigo.setup.SetupShell
import testigo.setup.ShellEnv
import testigo.setup.ShellKind
import testigo.setup.TerminalConfigurator
import java.nio.file.Path

class SetupCommand : CliktCommand(name = "setup") {

    override fun help(context: Context): String =
        "Prepara esta máquina: revisa Java, Maestro, el Android SDK, la debug key y la terminal, e instala lo que elijas, " +
            "con tu confirmación y sin root.\n\n" +
            "Todo queda en tu carpeta de usuario (~/.testigo, ~/.maestro, el SDK y tu perfil de la terminal). Es el único comando " +
            "de testigo que descarga algo, y solo de los sitios oficiales, con su SHA-256 verificado. Si falta Java, bin/testigo " +
            "ofrece descargarlo antes de empezar."

    private val check by option("--check", help = "Solo revisa: no instala ni cambia nada. Termina con código 2 si falta algo que testigo necesita.")
        .flag()
    private val yes by option("--yes", help = "Hace todo lo pendiente sin preguntar. La licencia del Android SDK la pide igual sdkmanager.")
        .flag()
    private val printEnv by option(
        "--print-env",
        help = "Muestra las variables de testigo para la terminal, sin cambiar nada. Sirve si no puedes editar tu perfil: " +
            "eval \"\$(testigo setup --print-env)\".",
    ).flag()

    override fun run() {
        val cwd = Path.of("").toAbsolutePath().normalize()
        val wanted = wanted(cwd)
        val inspect = { Machine.inspect(wanted = wanted) }
        if (printEnv) {
            val state = inspect()
            val kind = if (state.shell.kind == ShellKind.FISH) ShellKind.FISH else ShellKind.SH
            echo(ShellEnv.render(kind, SetupPlan.fullEntries(state), state.shellHome, loadedFrom = null).trimEnd())
            return
        }
        val info = terminal.terminalInfo
        val interactive = info.inputInteractive && info.outputInteractive
        val mode = when {
            check -> SetupMode.CHECK
            yes -> SetupMode.YES
            interactive -> SetupMode.INTERACTIVE
            else -> SetupMode.CHECK
        }
        val shell = ProcessSetupShell()
        val code = SetupFlow(
            inspect = inspect,
            asker = if (mode == SetupMode.INTERACTIVE) MordantAsker(terminal) else null,
            out = { echo(it) },
            fetcher = CurlFetcher(shell),
            shell = shell,
        ).run(mode, notInteractive = mode == SetupMode.CHECK && !check)
        if (code != ExitCode.OK) throw ProgramResult(code.code)
    }

    /** La versión de Maestro que fija el `testigo.yaml` de esta carpeta (o de una de arriba), con su archivo. */
    private fun wanted(cwd: Path): Pair<String, Path>? = runCatching {
        val project = ConfigLoader.load(null, cwd)
        val file = project.file
        val version = project.config.maestro.version?.takeIf { MaestroRelease.isVersion(it) }
        if (file != null && version != null) version to file else null
    }.getOrNull()
}

enum class SetupMode { INTERACTIVE, CHECK, YES }

/** El recorrido de `testigo setup`: revisa, deja elegir, confirma, hace y vuelve a revisar. */
class SetupFlow(
    private val inspect: () -> MachineState,
    private val asker: Asker?,
    private val out: (String) -> Unit,
    private val fetcher: Fetcher,
    private val shell: SetupShell,
    private val maestroVersion: (Path) -> String? = { MaestroCli(it).version() },
) {
    private var failures = 0

    /** El perfil de la terminal ya carga el entorno de testigo (lo escribió esta corrida o ya estaba). */
    private var terminalConfigured = false

    fun run(mode: SetupMode, notInteractive: Boolean = false): ExitCode {
        out(HEADER)
        val state = inspect()
        out(DoctorRenderer.section(DoctorSection("Esta máquina", SetupPlan.checks(state))).trimEnd())
        val actions = SetupPlan.actions(state)
        val failing = SetupPlan.checks(state).any { it.status == CheckStatus.FAIL }

        if (state.windows) {
            out("\nEn Windows, testigo setup todavía solo revisa: instala lo que falte a mano (docs/GUIA.md, sección 3).")
            return if (failing) ExitCode.ENVIRONMENT else ExitCode.OK
        }
        if (actions.isEmpty()) {
            out("\nNo hace falta instalar ni configurar nada.")
            out(NEXT_STEP)
            return if (failing) ExitCode.ENVIRONMENT else ExitCode.OK
        }
        if (mode == SetupMode.CHECK) {
            out("\nPendiente:")
            actions.forEach { out("  · " + summary(it, state)) }
            out(if (notInteractive) "\nEjecuta testigo setup en una terminal para elegir qué hacer, o testigo setup --yes para hacerlo todo." else "\nCon testigo setup lo haces, con tu confirmación.")
            return if (failing) ExitCode.ENVIRONMENT else ExitCode.OK
        }

        val selected = if (mode == SetupMode.YES) actions.filterNot { it.optional } else choose(actions, state) ?: return cancelled()
        if (selected.isEmpty() && mode == SetupMode.YES) {
            out("\nNo hace falta instalar ni configurar nada. Opcional, con testigo setup sin --yes:")
            actions.forEach { out("  · " + summary(it, state)) }
            out(NEXT_STEP)
            return if (failing) ExitCode.ENVIRONMENT else ExitCode.OK
        }
        if (selected.isEmpty()) {
            out("\nNo elegiste nada: no cambié nada.")
            return if (failing) ExitCode.ENVIRONMENT else ExitCode.OK
        }
        out("\nEsto es lo que voy a hacer. Nada usa root ni sudo.")
        selected.forEach { action -> details(action, state, selected).forEach(out) }
        if (mode == SetupMode.INTERACTIVE) {
            val asker = requireNotNull(asker)
            val answer = asker.one("¿Sigo?", listOf(Choice("Sí, hacerlo"), Choice("No, salir sin cambiar nada"))) ?: return cancelled()
            if (answer != 0) {
                out("No cambié nada.")
                return ExitCode.OK
            }
        }

        val results = selected.map { action -> execute(action) }
        val after = inspect()
        out(DoctorRenderer.section(DoctorSection("Así quedó", SetupPlan.checks(after))).trimEnd())
        results.filterNotNull().forEach(out)
        if (terminalConfigured) {
            val profile = after.shell.profile
            out(
                if (profile != null) {
                    "\nAbre una terminal nueva (o ejecuta: source ${ShellEnv.display(profile, after.shellHome)}) para usar testigo, maestro y adb desde cualquier carpeta."
                } else {
                    "\nAbre una terminal nueva para usar testigo, maestro y adb desde cualquier carpeta."
                },
            )
        }
        val stillFailing = SetupPlan.checks(after).any { it.status == CheckStatus.FAIL }
        if (!stillFailing) out(NEXT_STEP)
        return if (failures > 0 || stillFailing) ExitCode.ENVIRONMENT else ExitCode.OK
    }

    private fun cancelled(): ExitCode {
        out("Cancelado: no cambié nada.")
        return ExitCode.CANCELLED
    }

    private fun choose(actions: List<SetupAction>, state: MachineState): List<SetupAction>? {
        val asker = requireNotNull(asker)
        val names = actions.map { it.name }
        val width = names.maxOf { it.length } + 3
        val choices = actions.map { Choice(it.name.padEnd(width) + summary(it, state, withName = false), selected = !it.optional) }
        val picked = asker.many("Qué hago en esta máquina", choices) ?: return null
        return picked.sorted().map { actions[it] }
    }

    /** Una línea por tarea, para la lista de selección y para `--check`. */
    private fun summary(action: SetupAction, s: MachineState, withName: Boolean = true): String {
        val text = when (action) {
            is SetupAction.InstallMaestro -> {
                val what = action.replaces?.let { "cambia $it por ${action.version}" } ?: action.version
                "$what · descarga ${MaestroRelease.SIZE} de github.com a ${s.show(action.dir)}"
            }
            is SetupAction.InstallAndroidSdk -> {
                val from = action.cmdlineTools?.let { ", con sdkmanager (${it.size} de dl.google.com)" } ?: ", con el sdkmanager del SDK"
                packagesLabel(action.packages) + " en ${s.show(action.root)}$from"
            }
            is SetupAction.CreateDebugKeystore -> "crea ${s.show(action.file)} para instalar AABs; local, no descarga nada"
            is SetupAction.ConfigureTerminal -> {
                val profile = action.shell.profile?.let { " y una línea en ${ShellEnv.display(it, s.shellHome)}" }.orEmpty()
                "escribe ${ShellEnv.display(action.shell.envFile, s.shellHome)}$profile · " +
                    SetupPlan.missing(s, predicted(s, SetupPlan.actions(s))).map { it.label }.distinct().joinToString(", ").ifEmpty { "lo actualiza" }
            }
            is SetupAction.InstallSkill -> if (action.update) {
                "actualiza la skill testigo-flows en ${s.show(action.skill.target)}; la anterior queda en ${s.show(action.backups)}"
            } else {
                "copia la skill testigo-flows a ${s.show(action.skill.target)}, para escribir flows con IA (opcional); local"
            }
        }
        return if (withName) "${action.name}: $text" else text
    }

    private fun packagesLabel(packages: List<String>): String = packages.joinToString(" y ") {
        when (it) {
            CommandLineTools.PLATFORM_TOOLS_PACKAGE -> "platform-tools (adb)"
            CommandLineTools.BUILD_TOOLS_PACKAGE -> "build-tools ${CommandLineTools.BUILD_TOOLS} (aapt2)"
            else -> it
        }
    }

    /** El entorno que va a quedar si se hacen [actions]. */
    private fun predicted(s: MachineState, actions: List<SetupAction>): List<EnvEntry> = SetupPlan.envEntries(
        s,
        maestroInstalled = s.maestroManaged || actions.any { it is SetupAction.InstallMaestro },
        sdkInstalled = s.sdkExists || actions.any { it is SetupAction.InstallAndroidSdk },
    )

    private fun details(action: SetupAction, s: MachineState, selected: List<SetupAction>): List<String> = buildList {
        add("")
        when (action) {
            is SetupAction.InstallMaestro -> {
                add("Maestro ${action.version}")
                add("  · descarga ${MaestroRelease.zipUrl(action.version)} (${MaestroRelease.SIZE})")
                val hash = if (MaestroRelease.pinnedSha256(action.version) != null) "fijado en testigo" else "del checksums_sha256.txt del mismo release"
                add("  · verifica su SHA-256 ($hash) y deja bin/ y lib/ en ${s.show(action.dir)}")
            }
            is SetupAction.InstallAndroidSdk -> {
                add("Android SDK")
                action.cmdlineTools?.let {
                    add("  · descarga ${it.url} (${it.size}) y verifica su SHA-256")
                    add("  · deja las command-line tools en ${s.show(action.root.resolve("cmdline-tools/latest"))}")
                }
                add("  · sdkmanager instala ${action.packages.joinToString(" y ")} en ${s.show(action.root)}, desde dl.google.com")
                add("  · antes, sdkmanager te muestra la licencia del Android SDK: escribe y para aceptarla")
            }
            is SetupAction.CreateDebugKeystore -> {
                add("Debug key")
                add("  · keytool crea ${s.show(action.file)}, igual a la que crea Android Studio")
            }
            is SetupAction.InstallSkill -> {
                add("Claude Code")
                add("  · copia skills/${ClaudeSkill.NAME} de esta instalación de testigo a ${s.show(action.skill.target)}")
                if (action.update) add("  · antes mueve la copia actual a ${s.show(action.backups)}, por si la editaste")
                add("  · no descarga nada; Claude Code la usa desde la próxima sesión")
            }
            is SetupAction.ConfigureTerminal -> {
                add("Terminal (${action.shell.kind.label})")
                val entries = predicted(s, selected)
                add("  · escribe ${ShellEnv.display(action.shell.envFile, s.shellHome)}:")
                SetupPlan.rendered(s, entries).lines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { add("      $it") }
                action.shell.profile?.let { profile ->
                    val backup = if (s.profileText != null && !ShellEnv.hasBlock(s.profileText)) {
                        " (antes guarda una copia en ${ShellEnv.display(profile, s.shellHome)}${ShellEnv.BACKUP_SUFFIX})"
                    } else {
                        ""
                    }
                    if (!ShellEnv.hasBlock(s.profileText)) {
                        add("  · agrega al final de ${ShellEnv.display(profile, s.shellHome)}$backup:")
                        ShellEnv.block(action.shell.envFile, s.shellHome).lines().forEach { add("      $it") }
                    }
                }
            }
        }
    }

    /** Hace una tarea. Devuelve un aviso para el final, o null. */
    private fun execute(action: SetupAction): String? {
        val state = inspect()
        out("\n${action.name}")
        return try {
            when (action) {
                is SetupAction.InstallMaestro -> {
                    val executable = MaestroInstaller(fetcher) { out("  $it") }.install(action.version, action.dir)
                    val version = runCatching { maestroVersion(executable) }.getOrNull()
                    out("  ✔ Maestro ${version ?: action.version} en ${state.show(executable)}")
                    if (version != null && version != action.version) out("  ! maestro --version dice $version, no ${action.version}")
                    null
                }
                is SetupAction.InstallAndroidSdk -> {
                    val sdkmanager = AndroidSdkInstaller(fetcher, shell) { out("  $it") }
                    sdkmanager.install(action.root, action.packages, action.cmdlineTools, state.javaHome)
                    out("  ✔ Android SDK en ${state.show(action.root)}")
                    null
                }
                is SetupAction.CreateDebugKeystore -> {
                    DebugKeystoreCreator(shell).create(action.file, state.javaHome)
                    out("  ✔ ${state.show(action.file)}")
                    null
                }
                is SetupAction.ConfigureTerminal -> configureTerminal(state)
                is SetupAction.InstallSkill -> {
                    val backup = ClaudeSkill.install(action.skill.source, action.skill.target, action.backups)
                    out("  ✔ ${state.show(action.skill.target)}")
                    backup?.let { out("  ✔ la copia anterior quedó en ${state.show(it)}") }
                    "\nLa skill testigo-flows queda lista en tu próxima sesión de Claude Code: /testigo-flows desde la carpeta de tu suite."
                }
            }
        } catch (e: TestigoException) {
            failures++
            out("  ✖ ${e.message}")
            null
        }
    }

    private fun configureTerminal(state: MachineState): String? {
        val entries = SetupPlan.envEntries(state)
        val result = TerminalConfigurator.apply(state, entries)
        out("  ✔ ${ShellEnv.display(result.envFile, state.shellHome)}")
        val profile = result.profile ?: return null.also { terminalConfigured = true }
        val shown = ShellEnv.display(profile, state.shellHome)
        result.profileError?.let { error ->
            failures++
            out("  ✖ No pude modificar $shown: $error")
            return alternatives(state, shown)
        }
        when {
            result.profileChanged && result.backup != null -> out("  ✔ $shown (copia del original en ${ShellEnv.display(result.backup, state.shellHome)})")
            result.profileChanged -> out("  ✔ $shown")
            else -> out("  ✔ $shown ya lo cargaba")
        }
        terminalConfigured = true
        return null
    }

    /** Si no se puede tocar el perfil: lo que sí se puede hacer sin root. */
    private fun alternatives(state: MachineState, profile: String): String {
        val env = ShellEnv.display(state.shell.envFile, state.shellHome)
        val testigo = state.pathBin?.resolve("testigo")?.let { state.show(it) } ?: "bin/testigo"
        return """
            |
            |Sin tocar $profile, también funciona (nada de esto necesita root):
            |  · cargar el entorno en cada terminal nueva: . $env
            |  · o agregar esa línea al archivo de inicio que sí puedas editar
            |  · o no cargar nada: testigo encuentra Java, Maestro y el SDK en sus carpetas por defecto; ejecútalo con su ruta completa ($testigo)
        """.trimMargin()
    }

    companion object {
        const val HEADER = "testigo setup · revisa e instala lo que testigo necesita en esta máquina, sin root y con tu confirmación"
        const val NEXT_STEP = "\nSiguiente: en la carpeta de tu suite, testigo init (crea testigo.yaml y un flow de demo) y luego testigo doctor."
    }
}
