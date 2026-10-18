package testigo.doctor

import testigo.TestigoException
import testigo.ExitCode
import testigo.cli.DoctorRenderer
import testigo.config.AppConfig
import testigo.config.LoadedProject
import testigo.config.MaestroConfig
import testigo.config.NetworkConfig
import testigo.config.NetworkMode
import testigo.config.ProjectConfig
import testigo.maestro.FlowHeader
import testigo.maestro.FlowHeaderReader
import testigo.policy.FlowProblem
import testigo.policy.LintResult
import testigo.process.CommandResult
import testigo.process.CommandRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.time.Duration

class DoctorTest {

    @TempDir
    lateinit var tmp: Path

    private val pkg = "com.empresa.app"

    /** adb, aapt2 y el java de JAVA_HOME de mentira, con un dispositivo y la app instalada y debuggable. */
    private inner class FakeTools : CommandRunner {
        var javaSpec = "21"
        var javaVersion = "21.0.8"
        var devices = "List of devices attached\nabc123  device usb:1 product:x model:2201117TG device:y\n"
        var installed = true
        var runAs: CommandResult = ok("uid=10234(u0_a234) gid=10234(u0_a234)\n")
        var databases = "chucker.db\nchucker.db-shm\nchucker.db-wal\n"
        var root = false
        var envIgnored = true
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            val program = command.first()
            val args = command.drop(1)
            return when {
                program.endsWith("/bin/java") && args.first() == "-XshowSettings:properties" -> CommandResult(
                    0,
                    "",
                    "Property settings:\n    java.home = /opt/jdk\n    java.specification.version = $javaSpec\n    java.version = $javaVersion\n",
                )
                args.firstOrNull() == "-jar" && "dump" in args -> ok(
                    "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$pkg\" " +
                        "android:versionCode=\"70\" android:versionName=\"7.0.0-qa\"><application android:debuggable=\"true\"/></manifest>",
                )
                program.endsWith("/adb") -> adb(args.let { if (it.first() == "-s") it.drop(2) else it })
                program.endsWith("/aapt2") -> ok("package: name='$pkg' versionCode='70' versionName='7.0.0-qa'\napplication-debuggable\n")
                program == "git" && "rev-parse" in args -> ok("true\n")
                program == "git" && "check-ignore" in args -> CommandResult(if (envIgnored) 0 else 1, "", "")
                else -> error("comando inesperado: $command")
            }
        }

        private fun adb(args: List<String>): CommandResult = when (args.first()) {
            "version" -> ok("Android Debug Bridge version 1.0.41\nVersion 36.0.0-13206524\nInstalled as /sdk/platform-tools/adb\n")
            "devices" -> ok(devices)
            "shell" -> when (val shell = args[1]) {
                "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk" -> ok("2201117TG\n13\n33\n")
                "dumpsys package $pkg" -> if (installed) {
                    ok("Packages:\n  Package [$pkg] (abc):\n    userId=10234\n    versionCode=70 minSdk=24\n    versionName=7.0.0-qa\n")
                } else {
                    ok("Unable to find package: $pkg\n")
                }
                "run-as $pkg id" -> runAs
                "id -u" -> ok(if (root) "0\n" else "2000\n")
                "run-as $pkg ls databases", "ls /data/data/$pkg/databases" -> ok(databases)
                else -> error("shell inesperado: $shell")
            }
            else -> error("adb inesperado: $args")
        }
    }

    private fun ok(stdout: String) = CommandResult(0, stdout, "")

    private fun file(relative: String): Path = tmp.resolve(relative).also { it.parent.createDirectories(); it.writeText("x") }

    private val env by lazy {
        file("jdk/bin/java")
        file("sdk/platform-tools/adb")
        file("sdk/build-tools/36.1.0/aapt2")
        file("home/.maestro/bin/maestro")
        mapOf("JAVA_HOME" to tmp.resolve("jdk").toString(), "ANDROID_HOME" to tmp.resolve("sdk").toString(), "PATH" to "")
    }

    private fun project(
        maestro: String? = "2.11.0",
        capture: NetworkMode? = null,
        artifact: String = "../app/build/outputs/apk/qa/app-qa.apk",
    ): LoadedProject {
        file("suite/flows/home.yaml")
        file("suite/flows/perfil/cerrar_sesion.yaml")
        file("app/build/outputs/apk/qa/app-qa.apk")
        val config = ProjectConfig(
            app = AppConfig(artifact = artifact),
            maestro = MaestroConfig(version = maestro),
            network = NetworkConfig(capture = capture),
        )
        return LoadedProject(config, tmp.resolve("suite/testigo.yaml"), tmp.resolve("suite"))
    }

    /** Base de Chucker en el "dispositivo", con las columnas que se indiquen y dos llamadas. */
    private fun chuckerDb(columns: List<String> = listOf("requestDate", "method", "url", "host", "path", "responseCode", "requestBody", "responseBody")): Path {
        val db = tmp.resolve("device/chucker.db").also { it.parent.createDirectories(); it.deleteIfExists() }
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE transactions (id INTEGER PRIMARY KEY, ${columns.joinToString { "$it TEXT" }})")
                statement.executeUpdate("INSERT INTO transactions (${columns.first()}) VALUES ('1')")
                statement.executeUpdate("INSERT INTO transactions (${columns.first()}) VALUES ('2')")
            }
        }
        return db
    }

    private fun doctor(
        tools: FakeTools = FakeTools(),
        load: () -> LoadedProject = { project() },
        maestroVersion: String? = "2.11.0",
        db: Path? = chuckerDb(),
        environment: Map<String, String> = env,
        linter: (List<Path>) -> LintResult = { LintResult(it, emptyList()) },
    ) = Doctor(
        loadProject = load,
        device = null,
        headerReader = FlowHeaderReader { FlowHeader(name = it.fileName.toString().removeSuffix(".yaml")) },
        cwd = tmp,
        env = environment,
        home = tmp.resolve("home"),
        osName = "Linux",
        runner = tools,
        maestroVersion = { maestroVersion },
        bundletoolJar = { file("runner/tools/bundletool-all-1.18.3.jar") },
        copy = { command, target ->
            val name = command.last().substringAfterLast('/')
            if (db != null && name == "chucker.db") {
                Files.copy(db, target, StandardCopyOption.REPLACE_EXISTING)
                true
            } else {
                false
            }
        },
        linter = linter,
    ).run()

    private fun DoctorReport.check(name: String): Check = sections.flatMap { it.checks }.single { it.name == name }

    @Test
    fun `con todo en orden no hay problemas ni avisos y termina con 0`() {
        val report = doctor()
        println(DoctorRenderer.HEADER + report.sections.joinToString("") { DoctorRenderer.section(it) } + "\n" + DoctorRenderer.summary(report))

        assertEquals(listOf("Herramientas", "Dispositivo", "Proyecto · suite/testigo.yaml"), report.sections.map { it.title })
        assertEquals(
            listOf("Java", "Maestro", "Fijada", "adb", "aapt2", "bundletool", "Conectado", "Flows", "Build", "Instalada", "run-as", "Chucker"),
            report.sections.flatMap { it.checks }.map { it.name },
        )
        assertEquals(emptyList<Check>(), report.sections.flatMap { it.checks }.filter { it.status != CheckStatus.OK })
        assertEquals("21.0.8 · JAVA_HOME · /opt/jdk", report.check("Java").detail)
        assertEquals("2.11.0 · ~/.maestro/bin/maestro", report.check("Maestro").detail)
        assertEquals("36.0.0 · ${tmp.resolve("sdk/platform-tools/adb")}", report.check("adb").detail)
        assertEquals("abc123 · 2201117TG · Android 13 (API 33)", report.check("Conectado").detail)
        assertEquals("2 flows en flows/ · cumplen la política local-only", report.check("Flows").detail)
        assertEquals("$pkg · 7.0.0-qa (70) · APK · debuggable", report.check("Build").detail)
        assertEquals("chucker.db · 2 transacciones · esquema compatible", report.check("Chucker").detail)
        assertEquals(ExitCode.OK, report.exitCode)
        assertEquals("Todo listo para testigo run.", DoctorRenderer.summary(report))
    }

    @Test
    fun `Java menor a 17 es un problema`() {
        val tools = FakeTools().apply {
            javaSpec = "11"
            javaVersion = "11.0.22"
        }
        val report = doctor(tools)

        val java = report.check("Java")
        assertEquals(CheckStatus.FAIL, java.status)
        assertEquals("11.0.22 · JAVA_HOME · /opt/jdk", java.detail)
        assertTrue(java.fix!!.contains("Java 17 o más"), java.fix)
        assertEquals(ExitCode.ENVIRONMENT, report.exitCode)
        assertEquals("1 problema: corrige el problema antes de testigo run.", DoctorRenderer.summary(report))
    }

    @Test
    fun `JAVA_HOME sin bin java es un problema`() {
        val report = doctor(environment = env + ("JAVA_HOME" to tmp.resolve("no-existe").toString()))

        assertEquals(CheckStatus.FAIL, report.check("Java").status)
        assertTrue(report.check("Java").detail.startsWith("JAVA_HOME apunta a una carpeta sin bin/java"))
    }

    @Test
    fun `una version de Maestro distinta a la fijada es un aviso, y sin version fijada tambien`() {
        val distinta = doctor(maestroVersion = "2.10.0").check("Fijada")
        assertEquals(CheckStatus.WARN, distinta.status)
        assertEquals("testigo.yaml fija 2.11.0, pero tienes 2.10.0", distinta.detail)
        val otroArchivo = doctor(load = { project().copy(file = tmp.resolve("suite/testigo-qa.yaml")) }, maestroVersion = "2.10.0")
        assertEquals("testigo-qa.yaml fija 2.11.0, pero tienes 2.10.0", otroArchivo.check("Fijada").detail, "con --config dice qué archivo")

        val report = doctor(load = { project(maestro = null) })
        assertEquals(CheckStatus.WARN, report.check("Fijada").status)
        assertEquals("Agrega maestro.version: \"2.11.0\" para que todo el equipo use la misma.", report.check("Fijada").fix)
        assertEquals(ExitCode.OK, report.exitCode, "un aviso no cambia el código de salida")
        assertEquals("Listo para testigo run, con 1 aviso.", DoctorRenderer.summary(report))
    }

    @Test
    fun `sin Maestro es un problema y el arreglo instala la version fijada`() {
        check(env.isNotEmpty())
        tmp.resolve("home/.maestro/bin/maestro").deleteIfExists()
        val maestro = doctor().check("Maestro")

        assertEquals(CheckStatus.FAIL, maestro.status)
        assertEquals(
            "Instálalo con testigo setup desde esta carpeta: instala la 2.11.0, la de testigo.yaml.",
            maestro.fix,
        )
    }

    @Test
    fun `sin build-tools falta aapt2 y el APK queda sin leer`() {
        check(env.isNotEmpty())
        tmp.resolve("sdk/build-tools/36.1.0/aapt2").deleteIfExists()
        val report = doctor()

        assertEquals(CheckStatus.FAIL, report.check("aapt2").status)
        assertEquals("no hay Android SDK Build-Tools", report.check("aapt2").detail)
        assertEquals(CheckStatus.SKIP, report.check("Build").status)
        assertEquals("sin revisar: no sé el package de la app", report.check("run-as").detail)
        assertEquals(ExitCode.ENVIRONMENT, report.exitCode)
    }

    @Test
    fun `un AAB necesita app signing o la debug key para instalarse`() {
        file("app/build/outputs/bundle/qa/app-qa.aab")
        val aab = { project(artifact = "../app/build/outputs/bundle/qa/app-qa.aab") }

        val sinClave = doctor(load = aab)
        assertEquals("$pkg · 7.0.0-qa (70) · AAB · debuggable", sinClave.check("Build").detail)
        assertEquals(CheckStatus.FAIL, sinClave.check("Firma").status)
        assertTrue(sinClave.check("Firma").fix!!.contains("keytool -genkeypair"), sinClave.check("Firma").fix)

        file("home/.android/debug.keystore")
        val conClave = doctor(load = aab).check("Firma")
        assertEquals(CheckStatus.OK, conClave.status)
        assertEquals("debug key · ~/.android/debug.keystore", conClave.detail)
    }

    @Test
    fun `run-as que falla es un problema con Chucker y un aviso sin red`() {
        val notDebuggable = CommandResult(1, "", "run-as: package not debuggable: $pkg\n")
        val report = doctor(FakeTools().apply { runAs = notDebuggable })

        val failed = report.check("run-as")
        assertEquals(CheckStatus.FAIL, failed.status)
        assertEquals("falla: run-as: package not debuggable: $pkg", failed.detail)
        assertTrue(failed.fix!!.startsWith("La app instalada no es debuggable"), failed.fix)
        assertEquals("sin revisar: hace falta run-as", report.check("Chucker").detail)

        val conRoot = doctor(FakeTools().apply { runAs = notDebuggable; root = true })
        assertEquals(CheckStatus.OK, conRoot.check("run-as").status)
        assertTrue(conRoot.check("run-as").detail.endsWith("pero adbd corre como root: la base se lee con adb root"), conRoot.check("run-as").detail)
        assertEquals("chucker.db · 2 transacciones · esquema compatible", conRoot.check("Chucker").detail)

        val sinRed = doctor(FakeTools().apply { runAs = notDebuggable }, load = { project(capture = NetworkMode.NONE) })
        assertEquals(CheckStatus.WARN, sinRed.check("run-as").status)
        assertEquals("network.capture: none", sinRed.check("Chucker").detail)
        assertEquals(ExitCode.OK, sinRed.exitCode)
    }

    @Test
    fun `una base de Chucker con otro esquema es un problema, y sin base es un aviso`() {
        val otroEsquema = doctor(db = chuckerDb(listOf("requestDate", "method", "url"))).check("Chucker")
        assertEquals(CheckStatus.FAIL, otroEsquema.status)
        assertEquals(
            "chucker.db · a la tabla transactions le faltan host, path, responseCode, requestBody, responseBody",
            otroEsquema.detail,
        )

        val sinBase = doctor(FakeTools().apply { databases = "otra.db\n" }).check("Chucker")
        assertEquals(CheckStatus.WARN, sinBase.status)
        assertEquals("todavía no hay base de Chucker en $pkg", sinBase.detail)
    }

    @Test
    fun `sin dispositivo no revisa la app instalada, y con la app sin instalar tampoco run-as`() {
        val report = doctor(FakeTools().apply { devices = "List of devices attached\n\n" })
        assertEquals(CheckStatus.FAIL, report.check("Conectado").status)
        assertTrue(report.check("Conectado").detail.startsWith("No hay dispositivos conectados"))
        assertEquals("sin revisar: no hay dispositivo", report.check("Instalada").detail)

        val sinInstalar = doctor(FakeTools().apply { installed = false })
        assertEquals(CheckStatus.WARN, sinInstalar.check("Instalada").status)
        assertEquals("sin revisar: la app no está instalada", sinInstalar.check("run-as").detail)
        assertEquals("sin revisar: no hay dispositivo", report.check("run-as").detail)
        assertEquals(ExitCode.OK, sinInstalar.exitCode)
    }

    @Test
    fun `sin testigo yaml solo revisa herramientas y dispositivo, y uno invalido es un problema`() {
        val sinProyecto = doctor(load = { LoadedProject(ProjectConfig(), null, tmp) })
        assertEquals(listOf("testigo.yaml"), sinProyecto.sections.last().checks.map { it.name })
        assertEquals(CheckStatus.WARN, sinProyecto.check("testigo.yaml").status)
        assertTrue(sinProyecto.sections.flatMap { it.checks }.none { it.name == "Fijada" })

        val invalido = doctor(load = { throw TestigoException("testigo.yaml, línea 3, columna 1: clave desconocida 'netwrok'") })
        assertEquals(CheckStatus.FAIL, invalido.check("testigo.yaml").status)
        assertEquals(ExitCode.ENVIRONMENT, invalido.exitCode)
    }

    @Test
    fun `avisa si los usuarios de prueba de env testigo no estan en el gitignore`() {
        file("suite/.env.testigo")
        assertEquals(CheckStatus.OK, doctor().check(".gitignore").status)

        val report = doctor(FakeTools().apply { envIgnored = false })
        assertEquals(CheckStatus.WARN, report.check(".gitignore").status)
        assertEquals("Agrega .env.testigo al .gitignore de la suite.", report.check(".gitignore").fix)
    }

    @Test
    fun `nunca instala, desinstala ni escribe en el dispositivo`() {
        val tools = FakeTools()
        doctor(tools)
        val adb = tools.commands.filter { it.first().endsWith("/adb") }.map { it.joinToString(" ") }
        assertTrue(adb.none { " install" in it || " uninstall" in it || " push" in it || " rm " in it || "logcat -c" in it }, adb.toString())
    }

    @Test
    fun `lee la version de Java de cualquier formato`() {
        assertEquals(8, JavaProbe.major("1.8"))
        assertEquals(8, JavaProbe.major("1.8.0_392"))
        assertEquals(11, JavaProbe.major("11"))
        assertEquals(21, JavaProbe.major("21.0.8"))
        assertEquals(22, JavaProbe.major("22-ea"))
        assertNull(JavaProbe.parse("Error: no se encontró java"))
        val java8 = JavaProbe.parse("Property settings:\n    java.home = /usr/lib/jvm/java-8/jre\n    java.specification.version = 1.8\n    java.version = 1.8.0_392\n")
        assertEquals(JavaSettings("1.8.0_392", "1.8", "/usr/lib/jvm/java-8/jre"), java8)
        assertEquals(8, java8!!.major)
    }

    @Test
    fun `un subflow sin la configuracion que exige Maestro es FAIL en Flows`() {
        val report = doctor(
            linter = { flows ->
                LintResult(flows, emptyList(), listOf(FlowProblem(tmp.resolve("suite/flows/subflows/login.yaml"), null, "no empieza…", "Agrega al inicio:", listOf("---"))))
            },
        )

        val flows = report.check("Flows")
        assertEquals(CheckStatus.FAIL, flows.status)
        assertEquals("2 flows en flows/ · 1 archivo que Maestro no acepta: falta appId o ---", flows.detail)
        assertEquals("testigo run --dry-run dice cuál y qué agregar. Maestro lo exige también en los subflows.", flows.fix)
        assertEquals(ExitCode.ENVIRONMENT, report.exitCode)
    }
}
