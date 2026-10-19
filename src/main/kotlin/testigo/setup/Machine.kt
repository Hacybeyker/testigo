package testigo.setup

import testigo.android.AndroidSdk
import testigo.artifact.DebugKeystore
import testigo.doctor.Check
import testigo.doctor.CheckStatus
import testigo.maestro.MaestroCli
import testigo.policy.MaestroEnvironment
import testigo.util.tilde
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/** De dónde sacó testigo el Java con el que corre. [FOUND]: lo buscó `bin/testigo` porque la terminal no tenía uno 17+. */
enum class JavaOrigin { JAVA_HOME, PATH, FOUND }

private val SDK_PARTS = listOf("platform-tools", "build-tools", "cmdline-tools", "platforms", "emulator")

/** Lo que `testigo setup` sabe de esta máquina. Se arma con [Machine.inspect]; las reglas están en [SetupPlan]. */
data class MachineState(
    val windows: Boolean,
    /** `user.home`: donde viven las herramientas (`~/.maestro`, `~/Library/Android/sdk`). */
    val home: Path,
    /** `$HOME`: donde la terminal busca sus archivos de inicio. Casi siempre es la misma carpeta. */
    val shellHome: Path,
    /** `~/.testigo` (o `TESTIGO_HOME`). */
    val state: Path,
    /** La carpeta de esta instalación de testigo (la que tiene `bin/testigo`). */
    val appHome: Path?,
    val javaVersion: String,
    val javaHome: Path,
    val javaOrigin: JavaOrigin,
    /** El `JAVA_HOME` de la terminal que `bin/testigo` no pudo usar (no existe o es anterior a 17). */
    val javaIgnored: String?,
    val maestro: Path?,
    val maestroVersion: String?,
    /** `MAESTRO_DIR` o `~/.maestro`, donde `testigo setup` instala Maestro. */
    val maestroDir: Path,
    /** La versión que fija el `testigo.yaml` de esta carpeta o, si no hay, la que testigo usa por defecto. */
    val wantedMaestro: String,
    val wantedFrom: Path?,
    val sdkRoot: Path,
    val adb: Path?,
    val aapt2: Path?,
    val sdkmanager: Path?,
    val debugKeystore: Path?,
    val debugKeystoreTarget: Path,
    val env: Map<String, String>,
    val shell: ShellProfile,
    val envFileText: String?,
    val profileText: String?,
    val cmdlineTools: Download?,
    /** La skill de Claude Code, si esta instalación la trae y Claude Code está en la máquina. */
    val skill: ClaudeSkill? = null,
) {
    /** Un SDK de verdad, no solo la carpeta: tiene alguna de sus herramientas. */
    val sdkExists: Boolean get() = SDK_PARTS.any { sdkRoot.resolve(it).isDirectory() }
    val adbInSdk: Boolean get() = adb != null && adb.startsWith(sdkRoot)
    val maestroManaged: Boolean get() = maestro != null && maestro.toAbsolutePath().normalize().startsWith(maestroDir.toAbsolutePath().normalize())

    val pathDirs: List<Path>
        get() = env["PATH"].orEmpty().split(java.io.File.pathSeparatorChar).filter { it.isNotBlank() }.map { Path.of(it).toAbsolutePath().normalize() }

    fun onPath(dir: Path): Boolean = dir.toAbsolutePath().normalize() in pathDirs

    fun commandOnPath(name: String): Boolean = pathDirs.any { it.resolve(name).let { file -> file.isRegularFile() && file.isExecutable() } }

    fun show(path: Path): String = path.tilde(home)
}

object Machine {
    /**
     * Revisa la máquina sin cambiar nada. [wanted] es la versión de Maestro del `testigo.yaml` de la carpeta actual, con su
     * archivo; null si no hay. [maestroVersion] ejecuta `maestro --version`.
     */
    fun inspect(
        env: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        arch: String = System.getProperty("os.arch"),
        home: Path = Path.of(System.getProperty("user.home")),
        javaVersion: String = System.getProperty("java.version"),
        javaHomeProperty: Path = Path.of(System.getProperty("java.home")),
        appHome: Path? = appHome(env),
        wanted: Pair<String, Path>? = null,
        maestroVersion: (MaestroCli) -> String? = { it.version() },
    ): MachineState {
        val windows = osName.startsWith("Windows", ignoreCase = true)
        val shellHome = env["HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: home
        val state = env["TESTIGO_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: home.resolve(".testigo")

        val origin = when {
            !env["TESTIGO_JAVA_FOUND"].isNullOrBlank() -> JavaOrigin.FOUND
            !env["JAVA_HOME"].isNullOrBlank() -> JavaOrigin.JAVA_HOME
            else -> JavaOrigin.PATH
        }
        val javaHome = env["JAVA_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: javaHomeProperty

        val maestroDir = env["MAESTRO_DIR"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: home.resolve(".maestro")
        val maestro = runCatching { MaestroCli.locate(env, home, osName) }.getOrNull()
        val installedMaestro = maestro?.let { runCatching { maestroVersion(it) }.getOrNull() }

        val sdkRoot = sequenceOf(env["ANDROID_HOME"], env["ANDROID_SDK_ROOT"])
            .filterNotNull().filter { it.isNotBlank() }.map { Path.of(it) }.firstOrNull()
            ?: AndroidSdk.defaultRoot(home, env, osName)
        val exe = if (windows) ".exe" else ""
        val adb = sdkRoot.resolve("platform-tools/adb$exe").takeIf { it.isRegularFile() }
            ?: runCatching { AndroidSdk.locate(env, home, osName).adb }.getOrNull()
        val aapt2 = AndroidSdk.latestAapt2(sdkRoot.resolve("build-tools"), exe)

        val shell = ShellEnv.detect(env, shellHome, state, osName)
        return MachineState(
            windows = windows,
            home = home,
            shellHome = shellHome,
            state = state,
            appHome = appHome,
            javaVersion = javaVersion,
            javaHome = javaHome,
            javaOrigin = origin,
            javaIgnored = env["TESTIGO_JAVA_IGNORED"]?.takeIf { it.isNotBlank() },
            maestro = maestro?.executable,
            maestroVersion = installedMaestro,
            maestroDir = maestroDir,
            wantedMaestro = wanted?.first ?: MaestroRelease.DEFAULT_VERSION,
            wantedFrom = wanted?.second,
            sdkRoot = sdkRoot,
            adb = adb,
            aapt2 = aapt2,
            sdkmanager = AndroidSdkInstaller.sdkmanager(sdkRoot, windows),
            debugKeystore = DebugKeystore.locate(env, home.toString()),
            debugKeystoreTarget = home.resolve(".android/debug.keystore"),
            env = env,
            shell = shell,
            envFileText = shell.envFile.readOrNull(),
            profileText = shell.profile?.readOrNull(),
            cmdlineTools = CommandLineTools.download(osName, arch),
            skill = ClaudeSkill.inspect(env, home, appHome),
        )
    }

    /** `bin/testigo` exporta `TESTIGO_APP_HOME`; si no (desde el IDE), se deduce de dónde está el jar del runner. */
    fun appHome(env: Map<String, String>): Path? {
        env["TESTIGO_APP_HOME"]?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        val jar = runCatching { Path.of(Machine::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return null
        val candidate = jar.parent?.parent ?: return null
        return candidate.takeIf { jar.parent.name == "lib" && it.resolve("bin/testigo").isRegularFile() }
    }

    private fun Path.readOrNull(): String? = runCatching { if (isRegularFile()) readText() else null }.getOrNull()
}

/** Una tarea de `testigo setup`. Ninguna usa root: todo queda dentro de la carpeta del usuario. */
sealed class SetupAction(val name: String) {
    /** Opcional: no viene marcada y `--yes` no la hace. */
    open val optional: Boolean get() = false

    class InstallMaestro(val version: String, val dir: Path, val replaces: String?) : SetupAction("Maestro")

    class InstallAndroidSdk(val root: Path, val packages: List<String>, val cmdlineTools: Download?) : SetupAction("Android SDK")

    class CreateDebugKeystore(val file: Path) : SetupAction("Debug key")

    class ConfigureTerminal(val shell: ShellProfile) : SetupAction("Terminal")

    /** Copia la skill de Claude Code. Si reemplaza una copia distinta, la guarda antes en [backups]. */
    class InstallSkill(val skill: ClaudeSkill, val backups: Path) : SetupAction("Claude Code") {
        val update: Boolean get() = skill.status == ClaudeSkill.Status.OUTDATED

        /** Instalarla es opcional; actualizar una que ya está, no. */
        override val optional: Boolean get() = !update
    }
}

/** Las reglas de `testigo setup`: qué está bien, qué falta y qué se ofrece hacer. */
object SetupPlan {

    fun actions(s: MachineState): List<SetupAction> {
        if (s.windows) return emptyList()
        val actions = mutableListOf<SetupAction>()
        maestroAction(s)?.let { actions += it }
        sdkAction(s)?.let { actions += it }
        if (s.debugKeystore == null) actions += SetupAction.CreateDebugKeystore(s.debugKeystoreTarget)
        val predicted = envEntries(
            s,
            maestroInstalled = s.maestroManaged || actions.any { it is SetupAction.InstallMaestro },
            sdkInstalled = s.sdkExists || actions.any { it is SetupAction.InstallAndroidSdk },
        )
        if (terminalPending(s, predicted)) actions += SetupAction.ConfigureTerminal(s.shell)
        s.skill?.takeIf { it.status == ClaudeSkill.Status.MISSING || it.status == ClaudeSkill.Status.OUTDATED }?.let {
            actions += SetupAction.InstallSkill(it, s.state.resolve("backups"))
        }
        return actions
    }

    private fun maestroAction(s: MachineState): SetupAction.InstallMaestro? {
        val dir = s.maestroDir
        return when {
            s.maestro == null -> SetupAction.InstallMaestro(s.wantedMaestro, dir, replaces = null)
            // Sin testigo.yaml no se cambia una versión que ya funciona. Uno instalado con otra herramienta (Homebrew) no se toca.
            s.wantedFrom != null && s.maestroManaged && s.maestroVersion != null && s.maestroVersion != s.wantedMaestro ->
                SetupAction.InstallMaestro(s.wantedMaestro, dir, replaces = s.maestroVersion)
            else -> null
        }
    }

    private fun sdkAction(s: MachineState): SetupAction.InstallAndroidSdk? {
        val packages = buildList {
            if (s.adb == null) add(CommandLineTools.PLATFORM_TOOLS_PACKAGE)
            if (s.aapt2 == null) add(CommandLineTools.BUILD_TOOLS_PACKAGE)
        }
        if (packages.isEmpty()) return null
        return SetupAction.InstallAndroidSdk(s.sdkRoot, packages, s.cmdlineTools.takeIf { s.sdkmanager == null })
    }

    /**
     * Lo que el entorno de testigo define, en el orden en que se escribe. Lo que ya maneja un `env.sh` anterior se mantiene,
     * aunque la terminal actual ya lo tenga (porque justamente lo cargó de ahí).
     */
    fun envEntries(s: MachineState, maestroInstalled: Boolean = s.maestroManaged, sdkInstalled: Boolean = s.sdkExists): List<EnvEntry> {
        val managed = ShellEnv.managed(s.envFileText)
        val entries = mutableListOf<EnvEntry>()
        if (s.javaOrigin == JavaOrigin.FOUND || "JAVA_HOME" in managed) {
            entries += EnvEntry.Variable("JAVA_HOME", s.javaHome.toString())
        }
        if (sdkInstalled && (s.env["ANDROID_HOME"].isNullOrBlank() || "ANDROID_HOME" in managed)) {
            entries += EnvEntry.Variable("ANDROID_HOME", s.sdkRoot.toString())
        }
        MaestroEnvironment.REQUIRED.forEach { (name, value) ->
            if (s.env[name] != value || name in managed) entries += EnvEntry.Variable(name, value, "MAESTRO_*")
        }
        val maestroBin = s.maestroDir.resolve("bin")
        if (maestroInstalled && (!s.onPath(maestroBin) || "PATH:${ShellEnv.TAG_MAESTRO}" in managed)) {
            entries += EnvEntry.PathDir(maestroBin, ShellEnv.TAG_MAESTRO, "maestro en el PATH")
        }
        val platformTools = s.sdkRoot.resolve("platform-tools")
        val adb = if (s.windows) "adb.exe" else "adb"
        if (sdkInstalled && ((!s.commandOnPath(adb) && !s.onPath(platformTools)) || "PATH:${ShellEnv.TAG_ADB}" in managed)) {
            entries += EnvEntry.PathDir(platformTools, ShellEnv.TAG_ADB, "adb en el PATH")
        }
        s.appHome?.resolve("bin")?.let { bin ->
            if (!s.onPath(bin) || "PATH:${ShellEnv.TAG_TESTIGO}" in managed) entries += EnvEntry.PathDir(bin, ShellEnv.TAG_TESTIGO, "testigo en el PATH")
        }
        return entries
    }

    /**
     * Todo el entorno, también lo que la terminal ya tiene: para `--print-env`, cuando no se puede (o no se quiere)
     * tocar el perfil y las variables se cargan a mano o desde otro archivo.
     */
    fun fullEntries(s: MachineState): List<EnvEntry> = buildList {
        add(EnvEntry.Variable("JAVA_HOME", s.javaHome.toString()))
        if (s.sdkExists) add(EnvEntry.Variable("ANDROID_HOME", s.sdkRoot.toString()))
        MaestroEnvironment.REQUIRED.forEach { (name, value) -> add(EnvEntry.Variable(name, value, "MAESTRO_*")) }
        s.maestro?.parent?.let { add(EnvEntry.PathDir(it, ShellEnv.TAG_MAESTRO, "maestro en el PATH")) }
        s.adb?.parent?.let { add(EnvEntry.PathDir(it, ShellEnv.TAG_ADB, "adb en el PATH")) }
        s.appHome?.let { add(EnvEntry.PathDir(it.resolve("bin"), ShellEnv.TAG_TESTIGO, "testigo en el PATH")) }
    }

    /**
     * Lo que la terminal actual todavía no tiene. El `JAVA_HOME` que ve testigo cuando lo buscó `bin/testigo` es el que puso
     * ese script, no el de la terminal: ahí falta.
     */
    fun missing(s: MachineState, entries: List<EnvEntry>): List<EnvEntry> = entries.filter { entry ->
        when (entry) {
            is EnvEntry.Variable -> s.env[entry.name] != entry.value || (entry.name == "JAVA_HOME" && s.javaOrigin == JavaOrigin.FOUND)
            is EnvEntry.PathDir -> !s.onPath(entry.dir)
        }
    }

    fun rendered(s: MachineState, entries: List<EnvEntry>): String =
        ShellEnv.render(s.shell.kind, entries, s.shellHome, s.shell.profile)

    /** El entorno generado ya es el que corresponde y el perfil lo carga. */
    fun terminalUpToDate(s: MachineState, entries: List<EnvEntry>): Boolean =
        s.envFileText == rendered(s, entries) && (s.shell.profile == null || ShellEnv.hasBlock(s.profileText))

    private fun terminalPending(s: MachineState, entries: List<EnvEntry>): Boolean {
        if (entries.isEmpty()) return false
        if (terminalUpToDate(s, entries)) return false
        return missing(s, entries).isNotEmpty() || s.envFileText != null
    }

    // --- Lo que se muestra ----------------------------------------------------------------------------------

    fun checks(s: MachineState): List<Check> =
        listOf(java(s), maestro(s), sdk(s), adb(s), aapt2(s), debugKey(s), terminal(s)) + listOfNotNull(skill(s))

    private fun java(s: MachineState): Check {
        val where = "${s.javaVersion} · ${s.show(s.javaHome)}"
        s.javaIgnored?.let { ignored ->
            val fixed = s.shell.profile?.let { ShellEnv.display(it, s.shellHome) }.takeIf { terminalUpToDate(s, envEntries(s)) }
            return Check(
                "Java",
                CheckStatus.WARN,
                "$where · tu JAVA_HOME ($ignored) no es un Java 17 o más",
                fixed?.let { "Ya quedó corregido en $it: abre una terminal nueva." } ?: "La configuración de la terminal de testigo setup lo corrige.",
            )
        }
        return when (s.javaOrigin) {
            JavaOrigin.FOUND -> Check("Java", CheckStatus.OK, "$where · lo encontró testigo; tu terminal no tiene JAVA_HOME")
            JavaOrigin.JAVA_HOME -> Check("Java", CheckStatus.OK, "$where · JAVA_HOME")
            JavaOrigin.PATH -> Check("Java", CheckStatus.OK, "$where · el java del PATH")
        }
    }

    private fun maestro(s: MachineState): Check {
        val maestro = s.maestro ?: return Check("Maestro", CheckStatus.FAIL, "no está instalado")
        val installed = s.maestroVersion
            ?: return Check("Maestro", CheckStatus.WARN, "versión desconocida · ${s.show(maestro)}", "Prueba `maestro --version`.")
        val from = s.wantedFrom?.let { "su ${it.fileName}" }
        return when {
            from != null && installed != s.wantedMaestro && !s.maestroManaged -> Check(
                "Maestro",
                CheckStatus.WARN,
                "$installed · ${s.show(maestro)}, pero $from fija ${s.wantedMaestro}",
                "Ese Maestro no lo instaló testigo (¿Homebrew?): actualízalo con la herramienta con que lo instalaste.",
            )
            from != null && installed != s.wantedMaestro -> Check("Maestro", CheckStatus.WARN, "$installed · ${s.show(maestro)}, pero $from fija ${s.wantedMaestro}")
            else -> Check("Maestro", CheckStatus.OK, "$installed · ${s.show(maestro)}")
        }
    }

    private fun sdk(s: MachineState): Check = when {
        s.sdkExists -> Check("Android SDK", CheckStatus.OK, s.show(s.sdkRoot))
        s.windows -> Check("Android SDK", CheckStatus.FAIL, "no lo encontré en ${s.show(s.sdkRoot)}", "Instálalo con Android Studio.")
        else -> Check("Android SDK", CheckStatus.FAIL, "no lo encontré en ${s.show(s.sdkRoot)}")
    }

    private fun adb(s: MachineState): Check =
        s.adb?.let { Check("adb", CheckStatus.OK, s.show(it)) } ?: Check("adb", CheckStatus.FAIL, "falta Android SDK Platform-Tools")

    private fun aapt2(s: MachineState): Check =
        s.aapt2?.let { Check("aapt2", CheckStatus.OK, "build-tools ${it.parent.name} · ${s.show(it)}") }
            ?: Check("aapt2", CheckStatus.FAIL, "falta Android SDK Build-Tools: hace falta para leer APKs e instalar AABs")

    private fun debugKey(s: MachineState): Check =
        s.debugKeystore?.let { Check("Debug key", CheckStatus.OK, s.show(it)) }
            ?: Check("Debug key", CheckStatus.WARN, "no existe ${s.show(s.debugKeystoreTarget)}: hace falta para probar un AAB")

    private fun skill(s: MachineState): Check? {
        val skill = s.skill ?: return null
        val where = s.show(skill.target)
        return when (skill.status) {
            ClaudeSkill.Status.MISSING -> Check("Claude Code", CheckStatus.SKIP, "sin la skill testigo-flows (opcional, para escribir flows con IA)")
            ClaudeSkill.Status.CURRENT -> Check("Claude Code", CheckStatus.OK, "skill testigo-flows al día · $where")
            ClaudeSkill.Status.OUTDATED -> Check("Claude Code", CheckStatus.WARN, "la skill testigo-flows de $where no es la de esta versión de testigo")
            ClaudeSkill.Status.LINK -> {
                val link = ClaudeSkill.linkTarget(skill.target)?.let { " a ${s.show(it)}" }.orEmpty()
                Check("Claude Code", CheckStatus.OK, "skill testigo-flows · $where es un enlace$link: la actualizas tú")
            }
        }
    }

    private fun terminal(s: MachineState): Check {
        val actions = actions(s)
        val entries = envEntries(
            s,
            maestroInstalled = s.maestroManaged || actions.any { it is SetupAction.InstallMaestro },
            sdkInstalled = s.sdkExists || actions.any { it is SetupAction.InstallAndroidSdk },
        )
        val missing = missing(s, entries)
        val profile = s.shell.profile ?: s.shell.envFile
        return when {
            s.windows -> Check("Terminal", CheckStatus.SKIP, "en Windows, testigo setup todavía no configura la terminal")
            missing.isNotEmpty() && terminalUpToDate(s, entries) -> Check(
                "Terminal",
                CheckStatus.WARN,
                "configurada en ${ShellEnv.display(profile, s.shellHome)}, pero esta terminal se abrió antes",
                "Abre una terminal nueva o ejecuta: source ${ShellEnv.display(profile, s.shellHome)}",
            )
            missing.isNotEmpty() -> Check("Terminal", CheckStatus.WARN, "falta " + missing.map { it.label }.distinct().joinToString(", "))
            terminalPending(s, entries) -> Check("Terminal", CheckStatus.WARN, "${ShellEnv.display(s.shell.envFile, s.shellHome)} está desactualizado")
            else -> Check("Terminal", CheckStatus.OK, "las variables y el PATH que usa testigo ya están (${s.shell.kind.label})")
        }
    }
}
