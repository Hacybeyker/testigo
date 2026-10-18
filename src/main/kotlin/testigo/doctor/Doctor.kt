package testigo.doctor

import testigo.TestigoException
import testigo.ExitCode
import testigo.android.Adb
import testigo.android.AndroidSdk
import testigo.android.AppFiles
import testigo.android.DeviceInfo
import testigo.android.DeviceSelector
import testigo.android.InstalledPackage
import testigo.artifact.Artifact
import testigo.artifact.ArtifactFormat
import testigo.artifact.ArtifactInfo
import testigo.artifact.ArtifactInspector
import testigo.artifact.Bundletool
import testigo.artifact.DebugKeystore
import testigo.config.LoadedProject
import testigo.config.NetworkMode
import testigo.config.ProjectConfig
import testigo.config.ProjectLocator
import testigo.maestro.FlowDiscovery
import testigo.maestro.FlowHeaderReader
import testigo.maestro.MaestroCli
import testigo.network.ChuckerCapture
import testigo.network.ChuckerDatabase
import testigo.policy.FlowLinter
import testigo.policy.LintResult
import testigo.process.Cleanup
import testigo.process.CommandRunner
import testigo.process.ProcessCommandRunner
import testigo.util.displayFrom
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration.Companion.seconds

enum class CheckStatus { OK, WARN, FAIL, SKIP }

/** Una línea de `testigo doctor`: qué se revisó, cómo salió y, si falló, qué hacer. */
data class Check(val name: String, val status: CheckStatus, val detail: String, val fix: String? = null)

data class DoctorSection(val title: String, val checks: List<Check>)

data class DoctorReport(val sections: List<DoctorSection>) {
    private val checks get() = sections.flatMap { it.checks }
    val failures: Int get() = checks.count { it.status == CheckStatus.FAIL }
    val warnings: Int get() = checks.count { it.status == CheckStatus.WARN }

    /** Un problema (no un aviso) significa que `testigo run` fallaría: código 2, como un error de entorno. */
    val exitCode: ExitCode get() = if (failures > 0) ExitCode.ENVIRONMENT else ExitCode.OK
}

/**
 * `testigo doctor`: revisa lo que `testigo run` va a necesitar, sin instalar ni cambiar nada en la máquina ni en el
 * dispositivo. Herramientas (Java, Maestro, adb, aapt2, bundletool), el dispositivo y, con `testigo.yaml`, los flows,
 * el build, la app instalada, `run-as` y la base de Chucker. Todo es local: lo único que toca es adb.
 */
class Doctor(
    private val loadProject: () -> LoadedProject,
    private val device: String?,
    private val headerReader: FlowHeaderReader,
    private val cwd: Path,
    private val env: Map<String, String> = System.getenv(),
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val osName: String = System.getProperty("os.name"),
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val maestroVersion: (MaestroCli) -> String? = { it.version() },
    private val bundletoolJar: () -> Path = { Bundletool.locate() },
    /** Copia un archivo binario del dispositivo (`adb exec-out`). true si terminó bien. */
    private val copy: (List<String>, Path) -> Boolean = { command, target -> ChuckerCapture.execOut(command, target) },
    private val linter: (List<Path>) -> LintResult = { FlowLinter().lint(it) },
) {
    /** Lo que se va sabiendo y las secciones siguientes necesitan. */
    private var project: LoadedProject? = null
    private var projectError: String? = null
    private var sdk: AndroidSdk? = null
    private var adb: Adb? = null
    private var deviceInfo: DeviceInfo? = null
    private var bundletool: Bundletool? = null

    /** El archivo de configuración que se revisa: `testigo.yaml` o el de `--config`. */
    private val configName: String get() = project?.file?.fileName?.toString() ?: ProjectLocator.FILE_NAME

    /** [report] recibe cada sección apenas termina, para mostrarla sin esperar a las demás. */
    fun run(report: (DoctorSection) -> Unit = {}): DoctorReport {
        try {
            project = loadProject()
        } catch (e: TestigoException) {
            projectError = e.message
        }
        val sections = listOf(::tools, ::deviceSection, ::projectSection).map { section -> section().also(report) }
        return DoctorReport(sections)
    }

    // --- Herramientas ---------------------------------------------------------------------------------------

    private fun tools(): DoctorSection = DoctorSection(
        "Herramientas",
        listOf(java()) + maestro() + listOf(adbCheck(), aapt2(), bundletoolCheck()),
    )

    private fun java(): Check {
        val command = JavaProbe.command(env, osName)
        if (command == null) {
            val javaHome = env["JAVA_HOME"]?.takeIf { it.isNotBlank() }
            return if (javaHome != null) {
                Check("Java", CheckStatus.FAIL, "JAVA_HOME apunta a una carpeta sin bin/java: $javaHome", JAVA_FIX)
            } else {
                Check("Java", CheckStatus.FAIL, "no hay JAVA_HOME ni java en el PATH", JAVA_FIX)
            }
        }
        // bin/testigo buscó un Java 17 o más porque la terminal no tenía uno: testigo lo usa igual, pero la terminal no lo sabe.
        val source = when {
            !env["TESTIGO_JAVA_FOUND"].isNullOrBlank() -> "lo encontró testigo (testigo setup lo deja en la terminal)"
            command.fromJavaHome -> "JAVA_HOME"
            else -> "PATH"
        }
        val settings = try {
            JavaProbe.settings(command.java, runner)
        } catch (e: TestigoException) {
            null
        } ?: return Check("Java", CheckStatus.FAIL, "${tilde(command.java)} ($source) no arranca", JAVA_FIX)

        val where = "$source · " + tilde(settings.home?.let { Path.of(it) } ?: command.java)
        val version = settings.version ?: settings.specification ?: "?"
        val major = settings.major
        return when {
            major == null -> Check("Java", CheckStatus.WARN, "$version · $where", "No pude leer la versión: Maestro y bundletool necesitan Java ${JavaProbe.MIN_MAJOR} o más.")
            major < JavaProbe.MIN_MAJOR -> Check(
                "Java",
                CheckStatus.FAIL,
                "$version · $where",
                "Maestro, bundletool y el runner necesitan Java ${JavaProbe.MIN_MAJOR} o más. $JAVA_FIX",
            )
            else -> Check("Java", CheckStatus.OK, "$version · $where")
        }
    }

    private fun maestro(): List<Check> {
        val maestro = try {
            MaestroCli.locate(env, home, osName)
        } catch (e: TestigoException) {
            val pinned = project?.takeIf { it.file != null }?.config?.maestro?.version
            val fix = if (pinned != null) "Instálalo con testigo setup desde esta carpeta: instala la $pinned, la de $configName." else "Instálalo con testigo setup."
            return listOf(Check("Maestro", CheckStatus.FAIL, "no está instalado", fix))
        }
        val installed = try {
            maestroVersion(maestro)
        } catch (e: Exception) {
            null
        }
        val found = if (installed == null) {
            Check("Maestro", CheckStatus.WARN, "versión desconocida · ${tilde(maestro.executable)}", "Prueba `maestro --version`: si falla, revisa Java.")
        } else {
            Check("Maestro", CheckStatus.OK, "$installed · ${tilde(maestro.executable)}")
        }
        val project = project?.takeIf { it.file != null } ?: return listOf(found)
        val pinned = project.config.maestro.version
        val versionCheck = when {
            pinned == null -> Check(
                "Fijada",
                CheckStatus.WARN,
                "$configName no fija la versión de Maestro",
                "Agrega maestro.version${installed?.let { ": \"$it\"" }.orEmpty()} para que todo el equipo use la misma.",
            )
            installed == null -> null
            pinned != installed -> Check(
                "Fijada",
                CheckStatus.WARN,
                "$configName fija $pinned, pero tienes $installed",
                "Instala esa versión con testigo setup desde esta carpeta o, si el equipo ya cambió de versión, actualiza maestro.version.",
            )
            else -> Check("Fijada", CheckStatus.OK, "$pinned, la de $configName")
        }
        return listOfNotNull(found, versionCheck)
    }

    private fun adbCheck(): Check {
        val sdk = try {
            AndroidSdk.locate(env, home, osName)
        } catch (e: TestigoException) {
            return Check("adb", CheckStatus.FAIL, "no se encontró", "Instálalo con testigo setup (Android SDK Platform-Tools), o define ANDROID_HOME si el SDK está en otra carpeta.")
        }
        this.sdk = sdk
        val adb = Adb(sdk.adb, runner).also { this.adb = it }
        val version = try {
            runner.run(listOf(adb.path.toString(), "version"), 30.seconds)
                .takeIf { it.ok }?.stdout
                ?.let { Regex("(?m)^Version (\\S+)").find(it)?.groupValues?.get(1)?.substringBefore('-') }
        } catch (e: TestigoException) {
            this.adb = null
            return Check("adb", CheckStatus.FAIL, "${tilde(sdk.adb)} no arranca: ${e.message}", "Reinstala Android SDK Platform-Tools.")
        }
        return Check("adb", CheckStatus.OK, listOfNotNull(version, tilde(sdk.adb)).joinToString(" · "))
    }

    private fun aapt2(): Check {
        val sdk = sdk ?: return Check("aapt2", CheckStatus.SKIP, "sin revisar: falta el Android SDK")
        val aapt2 = sdk.aapt2OrNull ?: return Check(
            "aapt2",
            CheckStatus.FAIL,
            "no hay Android SDK Build-Tools",
            "Instálalo con testigo setup (Android SDK Build-Tools): hace falta para leer APKs e instalar AABs.",
        )
        return Check("aapt2", CheckStatus.OK, "build-tools ${aapt2.parent.name} · ${tilde(aapt2)}")
    }

    private fun bundletoolCheck(): Check {
        val jar = try {
            bundletoolJar()
        } catch (e: TestigoException) {
            return Check("bundletool", CheckStatus.FAIL, "falta en esta instalación del runner", "Vuelve a generarla con ./gradlew installDist.")
        }
        if (!jar.isRegularFile()) {
            return Check("bundletool", CheckStatus.FAIL, "no existe ${tilde(jar)}", "Revisa TESTIGO_BUNDLETOOL o vuelve a generar el runner.")
        }
        bundletool = Bundletool(runner, { jar })
        val version = jar.name.removePrefix("bundletool-all-").removeSuffix(".jar")
        return Check("bundletool", CheckStatus.OK, "$version · ${tilde(jar)}")
    }

    // --- Dispositivo ----------------------------------------------------------------------------------------

    private fun deviceSection(): DoctorSection {
        val adb = adb ?: return DoctorSection("Dispositivo", listOf(Check("Dispositivo", CheckStatus.SKIP, "sin revisar: falta adb")))
        val check = try {
            val selected = DeviceSelector.select(adb.devices(), device)
            val info = adb.deviceInfo(selected.serial).also { deviceInfo = it }
            Check("Conectado", CheckStatus.OK, info.label)
        } catch (e: TestigoException) {
            Check("Conectado", CheckStatus.FAIL, e.message.orEmpty().lines().first(), deviceFix(e.message.orEmpty()))
        }
        return DoctorSection("Dispositivo", listOf(check))
    }

    private fun deviceFix(message: String): String? {
        val extra = message.lines().drop(1).joinToString("; ") { it.trim() }.takeIf { it.isNotBlank() }
        return extra?.let { "Conectados: $it" }
    }

    // --- Proyecto -------------------------------------------------------------------------------------------

    private fun projectSection(): DoctorSection {
        projectError?.let { error ->
            return DoctorSection(
                "Proyecto",
                listOf(Check(ProjectLocator.FILE_NAME, CheckStatus.FAIL, error.lines().first(), "Corrige ${ProjectLocator.FILE_NAME}; testigo run falla igual.")),
            )
        }
        val project = project
        if (project?.file == null) {
            return DoctorSection(
                "Proyecto",
                listOf(
                    Check(
                        ProjectLocator.FILE_NAME,
                        CheckStatus.WARN,
                        "no lo encontré desde esta carpeta: solo revisé las herramientas y el dispositivo",
                        "Crea la suite con testigo init, o corre testigo doctor desde su carpeta o con --config.",
                    ),
                ),
            )
        }
        val config = project.config
        val chucker = config.network.capture != NetworkMode.NONE
        val checks = mutableListOf<Check>()
        checks += flows(project)
        val (buildCheck, app) = build(project)
        checks += buildCheck
        signing(project, app)?.let { checks += it }
        val packageName = app?.packageName ?: config.app.packageName.takeIf { it != "auto" }
        val installed = installed(packageName, app).also { checks += it.first }.second
        val files = runAs(packageName, installed, chucker).also { checks += it.first }.second
        checks += chuckerDb(packageName, files, chucker, config.network.chuckerDb)
        gitignore(project)?.let { checks += it }
        return DoctorSection("Proyecto · ${project.file.displayFrom(cwd)}", checks)
    }

    private fun flows(project: LoadedProject): Check {
        val folder = project.config.flows ?: ProjectConfig.DEFAULT_FLOWS
        val root = project.dir.resolve(folder).normalize()
        if (!root.isDirectory() && !root.isRegularFile()) {
            return Check("Flows", CheckStatus.FAIL, "no existe $folder", "Crea la carpeta o corrige flows en $configName.")
        }
        val discovery = FlowDiscovery.discover(listOf(root), headerReader)
        val flows = discovery.flows
        if (flows.isEmpty()) {
            return Check("Flows", CheckStatus.WARN, "no hay flows (.yaml) en $folder", "Agrega un flow o corrige flows en $configName.")
        }
        val lint = linter(flows.map { it.path })
        val count = quantity(flows.size, "flow", "flows")
        return when {
            !lint.passed -> Check(
                "Flows",
                CheckStatus.FAIL,
                "$count en $folder · ${quantity(lint.violations.size, "violación", "violaciones")} de la política local-only",
                "testigo run --dry-run las muestra con archivo y línea.",
            )
            lint.problems.isNotEmpty() -> Check(
                "Flows",
                CheckStatus.FAIL,
                "$count en $folder · ${quantity(lint.problems.size, "archivo", "archivos")} que Maestro no acepta: falta appId o ---",
                "testigo run --dry-run dice ${if (lint.problems.size == 1) "cuál" else "cuáles"} y qué agregar. Maestro lo exige también en los subflows.",
            )
            discovery.unreadable.isNotEmpty() -> Check(
                "Flows",
                CheckStatus.WARN,
                "$count en $folder · ${discovery.unreadable.size} con un header que no se puede leer",
                "testigo list y testigo run --dry-run dicen cuáles.",
            )
            else -> Check("Flows", CheckStatus.OK, "$count en $folder · cumplen la política local-only")
        }
    }

    private fun build(project: LoadedProject): Pair<Check, ArtifactInfo?> {
        val configured = project.config.app.artifact ?: return Check(
            "Build",
            CheckStatus.WARN,
            "$configName no define app.artifact",
            "Defínelo o pasa --app a testigo run.",
        ) to null
        val path = project.dir.resolve(configured).normalize()
        val format = ArtifactFormat.of(path.name)
            ?: return Check("Build", CheckStatus.FAIL, "app.artifact debe ser un .apk o un .aab: $configured") to null
        if (!path.isRegularFile()) {
            return Check("Build", CheckStatus.FAIL, "no existe ${path.displayFrom(cwd)}", "Genera el build o corrige app.artifact.") to null
        }
        val sdk = sdk
        val bundletool = bundletool
        if ((format == ArtifactFormat.APK && sdk?.aapt2OrNull == null) || (format == ArtifactFormat.AAB && bundletool == null)) {
            val tool = if (format == ArtifactFormat.APK) "aapt2" else "bundletool"
            return Check("Build", CheckStatus.SKIP, "${path.displayFrom(cwd)} sin leer: falta $tool") to null
        }
        val app = try {
            ArtifactInspector(runner, { sdk!!.aapt2 }, bundletool ?: Bundletool(runner)).inspect(Artifact(path, format))
        } catch (e: TestigoException) {
            return Check("Build", CheckStatus.FAIL, e.message.orEmpty().lines().first(), "Revisa que ${path.displayFrom(cwd)} sea un build válido.") to null
        }
        val expected = project.config.app.packageName
        if (expected != "auto" && expected != app.packageName) {
            return Check(
                "Build",
                CheckStatus.FAIL,
                "${app.label}, pero $configName dice app.package: $expected",
                "Corrige app.package o app.artifact.",
            ) to app
        }
        if (!app.debuggable && project.config.network.capture != NetworkMode.NONE) {
            return Check(
                "Build",
                CheckStatus.WARN,
                "${app.label} · no es debuggable",
                "Sin run-as, la red solo se lee con adb root (emulador sin Play Store). Para la red, usa un build debuggable, de cualquier build type.",
            ) to app
        }
        val debuggable = if (app.debuggable) "debuggable" else "no debuggable"
        return Check("Build", CheckStatus.OK, "${app.label} · $debuggable") to app
    }

    /** Solo para un AAB: los APKs que genera bundletool se firman con app.signing o con la debug key. */
    private fun signing(project: LoadedProject, app: ArtifactInfo?): Check? {
        if (app?.artifact?.format != ArtifactFormat.AAB) return null
        val signing = project.config.app.signing
        if (signing != null) {
            val keystore = project.dir.resolve(signing.keystore).normalize()
            return if (keystore.isRegularFile()) {
                Check("Firma", CheckStatus.OK, "app.signing · ${keystore.displayFrom(cwd)} (${signing.alias})")
            } else {
                Check("Firma", CheckStatus.FAIL, "no existe el keystore de app.signing: ${keystore.displayFrom(cwd)}", "Corrige app.signing.keystore en $configName.")
            }
        }
        val debugKey = DebugKeystore.locate(env, home.toString()) ?: return Check(
            "Firma",
            CheckStatus.FAIL,
            "no hay app.signing ni ~/.android/debug.keystore: los APKs del AAB saldrían sin firma",
            "Crea la debug key con testigo setup (o a mano: ${DebugKeystore.CREATE_COMMAND}), o define app.signing en $configName.",
        )
        return Check("Firma", CheckStatus.OK, "debug key · ${tilde(debugKey)}")
    }

    private fun installed(packageName: String?, app: ArtifactInfo?): Pair<Check, InstalledPackage?> {
        val adb = adb
        val device = deviceInfo
        if (packageName == null) return Check("Instalada", CheckStatus.SKIP, "sin revisar: no sé el package de la app") to null
        if (adb == null || device == null) return Check("Instalada", CheckStatus.SKIP, "sin revisar: no hay dispositivo") to null
        val installed = try {
            adb.installedPackage(device.serial, packageName)
        } catch (e: TestigoException) {
            return Check("Instalada", CheckStatus.WARN, "no pude consultar $packageName: ${e.message}") to null
        } ?: return Check(
            "Instalada",
            CheckStatus.WARN,
            "$packageName todavía no está en el dispositivo",
            "testigo run la instala; luego vuelve a correr doctor para revisar run-as y Chucker.",
        ) to null
        val version = listOfNotNull(installed.versionName, installed.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "versión desconocida" }
        val differs = app != null && (installed.versionCode != app.versionCode || installed.versionName != app.versionName)
        val note = if (differs) " · distinta del build: testigo run instala el build" else ""
        return Check("Instalada", CheckStatus.OK, "$packageName $version$note") to installed
    }

    /**
     * `run-as <package> id` responde `uid=...` si la app instalada es debuggable. Si no, sirve que adbd corra como
     * root (ver [AppFiles]). Devuelve el acceso para leer la base de Chucker, o null.
     */
    private fun runAs(packageName: String?, installed: InstalledPackage?, chucker: Boolean): Pair<Check, AppFiles?> {
        val adb = adb
        val device = deviceInfo
        val skipped = when {
            packageName == null -> "no sé el package de la app"
            adb == null || device == null -> "no hay dispositivo"
            installed == null -> "la app no está instalada"
            else -> null
        }
        if (skipped != null || packageName == null || adb == null || device == null) {
            return Check("run-as", CheckStatus.SKIP, "sin revisar: $skipped") to null
        }
        val access = try {
            AppFiles.detect(packageName) { args -> adb.shell(device.serial, args.joinToString(" ")) }
        } catch (e: TestigoException) {
            return Check("run-as", if (chucker) CheckStatus.FAIL else CheckStatus.WARN, "no respondió: ${e.message}") to null
        }
        val files = access.files
        if (files?.mode == AppFiles.Mode.RUN_AS) return Check("run-as", CheckStatus.OK, "funciona con $packageName") to files
        val reason = access.runAsError ?: "sin respuesta"
        if (files != null) {
            return Check("run-as", CheckStatus.OK, "falla ($reason), pero adbd corre como root: la base se lee con adb root") to files
        }

        val fix = when {
            "not debuggable" in reason ->
                "La app instalada no es debuggable: instala un build debuggable (testigo run lo hace si app.artifact lo es) " +
                    "o, en un emulador sin Play Store, ejecuta adb root."
            "unknown package" in reason -> "El dispositivo no reconoce $packageName: reinstala la app."
            else -> "Revisa que la app instalada sea un build debuggable y que el dispositivo permita run-as."
        }
        val status = if (chucker) CheckStatus.FAIL else CheckStatus.WARN
        return Check("run-as", status, "falla: $reason", fix + if (chucker) "" else " Solo hace falta para la red con Chucker.") to null
    }

    private fun chuckerDb(packageName: String?, app: AppFiles?, chucker: Boolean, configured: String): Check {
        if (!chucker) return Check("Chucker", CheckStatus.SKIP, "network.capture: none")
        val adb = adb
        val device = deviceInfo
        if (packageName == null || app == null || adb == null || device == null) {
            return Check("Chucker", CheckStatus.SKIP, "sin revisar: hace falta run-as")
        }
        val listing = try {
            adb.shell(device.serial, app.command("ls", app.path("databases")).joinToString(" "))
        } catch (e: TestigoException) {
            return Check("Chucker", CheckStatus.FAIL, "no pude listar databases/: ${e.message}")
        }
        if (!listing.ok) {
            return Check("Chucker", CheckStatus.FAIL, "no pude listar databases/: ${listing.output.lines().firstOrNull().orEmpty()}")
        }
        val files = listing.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = ChuckerCapture.databaseName(files, configured)
            ?: return if (configured == "auto") {
                Check(
                    "Chucker",
                    CheckStatus.WARN,
                    "todavía no hay base de Chucker en $packageName",
                    "Abre la app y haz una llamada al backend. Si sigue sin aparecer, revisa que el build traiga Chucker.",
                )
            } else {
                Check("Chucker", CheckStatus.WARN, "no existe databases/$configured", "Revisa network.chucker_db en $configName.")
            }

        val raw = Files.createTempDirectory("testigo-doctor-")
        Cleanup.register(raw) {
            raw.listDirectoryEntries().forEach { it.deleteIfExists() }
            raw.deleteIfExists()
        }
        try {
            listOf(name, "$name-wal", "$name-shm").filter { it in files }.forEach { file ->
                val target = raw.resolve(file)
                val command = listOf(adb.path.toString(), "-s", device.serial, "exec-out") + app.command("cat", app.path("databases/$file"))
                if (!copy(command, target)) target.deleteIfExists()
            }
            if (!raw.resolve(name).isRegularFile()) {
                return Check("Chucker", CheckStatus.FAIL, "no pude copiar databases/$name con ${app.mode.label}")
            }
            val schema = ChuckerDatabase.inspect(raw.resolve(name)) ?: return Check(
                "Chucker",
                CheckStatus.FAIL,
                "$name no tiene la tabla transactions",
                "¿Cambió la versión de Chucker? El runner lee el esquema de Chucker 4.x.",
            )
            if (schema.missing.isNotEmpty()) {
                return Check(
                    "Chucker",
                    CheckStatus.FAIL,
                    "$name · a la tabla transactions le faltan ${schema.missing.joinToString(", ")}",
                    "¿Cambió la versión de Chucker? El runner lee el esquema de Chucker 4.x.",
                )
            }
            return Check("Chucker", CheckStatus.OK, "$name · ${quantity(schema.transactions.toInt(), "transacción", "transacciones")} · esquema compatible")
        } catch (e: java.sql.SQLException) {
            return Check("Chucker", CheckStatus.FAIL, "no pude leer $name: ${e.message}")
        } finally {
            raw.listDirectoryEntries().forEach { it.deleteIfExists() }
            raw.deleteIfExists()
            Cleanup.unregister(raw)
        }
    }

    /**
     * Si la suite está en un repo de git, `.env.testigo` (los usuarios de prueba) tiene que estar en el `.gitignore`.
     * `evidence/` no hace falta: el runner le crea su propio `.gitignore`. Solo lee git local.
     */
    private fun gitignore(project: LoadedProject): Check? {
        val envFile = project.dir.resolve(ENV_FILE)
        if (!envFile.isRegularFile()) return null
        val git = { args: List<String> -> runner.run(listOf("git", "-C", project.dir.toString()) + args, 10.seconds) }
        return try {
            if (!git(listOf("rev-parse", "--is-inside-work-tree")).ok) return null
            if (git(listOf("check-ignore", "-q", ENV_FILE)).ok) {
                Check(".gitignore", CheckStatus.OK, "$ENV_FILE no se sube al repo")
            } else {
                Check(
                    ".gitignore",
                    CheckStatus.WARN,
                    "$ENV_FILE no está en el .gitignore: los usuarios de prueba podrían terminar en un commit",
                    "Agrega $ENV_FILE al .gitignore de la suite.",
                )
            }
        } catch (e: TestigoException) {
            null
        }
    }

    /** `~/Library/Android/sdk/platform-tools/adb` */
    private fun tilde(path: Path): String {
        val absolute = path.toAbsolutePath().normalize()
        val home = home.toAbsolutePath().normalize()
        return if (absolute.startsWith(home)) "~/" + home.relativize(absolute).toString().replace('\\', '/') else absolute.toString()
    }

    private fun quantity(n: Int, singular: String, plural: String): String = "$n ${if (n == 1) singular else plural}"

    private companion object {
        const val ENV_FILE = ".env.testigo"
        const val JAVA_FIX = "Ejecuta testigo setup, o apunta JAVA_HOME a un JDK 17 o más nuevo (por ejemplo el jbr de Android Studio)."
    }
}
