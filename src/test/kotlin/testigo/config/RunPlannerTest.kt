package testigo.config

import testigo.TestigoException
import testigo.artifact.ArtifactFormat
import testigo.maestro.FlowHeader
import testigo.maestro.FlowHeaderReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.io.path.writeText

class RunPlannerTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var suite: Path

    /** Lee los tags del nombre del archivo: "registro__smoke_onboarding.yaml" → [smoke, onboarding]. */
    private val reader = FlowHeaderReader { path ->
        val tags = path.name.substringBeforeLast('.').substringAfter("__", "").split('_').filter { it.isNotEmpty() }
        FlowHeader(appId = "com.empresa.app", tags = tags)
    }

    @BeforeEach
    fun setUp() {
        suite = tmp.resolve("suite").createDirectories()
        suite.resolve("flows/subflows").createDirectories()
        suite.resolve("flows/subflows/login.yaml").createFile()
        suite.resolve("flows/registro__smoke_onboarding.yaml").createFile()
        suite.resolve("flows/actualizar__perfil.yaml").createFile()
        suite.resolve("build").createDirectories()
        suite.resolve("build/app.aab").createFile()
    }

    private fun project(config: ProjectConfig = ProjectConfig(app = AppConfig(artifact = "build/app.aab"))) =
        LoadedProject(config, suite.resolve("testigo.yaml"), suite)

    private fun plan(options: RunOptions = RunOptions(), project: LoadedProject = project(), cwd: Path = suite) =
        RunPlanner(cwd, reader).plan(options, project)

    @Test
    fun `sin flags usa el perfil dev por defecto`() {
        val plan = plan()

        assertEquals(Resolved(Profile.DEV, Source.DEFAULT), plan.profile)
        assertEquals(Resolved(false, Source.PROFILE), plan.record)
        assertEquals(Resolved(NetworkMode.CHUCKER, Source.PROFILE), plan.network)
        assertEquals(Resolved(true, Source.PROFILE), plan.logs)
    }

    @Test
    fun `la calidad del video sale del flag, de testigo yaml o es 4 Mbps`() {
        val config = ProjectConfig(app = AppConfig(artifact = "build/app.aab"), video = VideoConfig(bitrateMbps = 6))

        assertEquals(Resolved(4, Source.DEFAULT), plan().videoBitrate)
        assertEquals(Resolved(6, Source.PROJECT), plan(project = project(config)).videoBitrate)
        assertEquals(Resolved(8, Source.FLAG), plan(RunOptions(videoBitrate = 8), project(config)).videoBitrate)
        assertThrows<TestigoException> { plan(RunOptions(videoBitrate = 12)) }
    }

    @Test
    fun `el formato de la red sale del flag, de testigo yaml o es txt`() {
        val config = ProjectConfig(app = AppConfig(artifact = "build/app.aab"), network = NetworkConfig(format = NetworkFormat.HAR))

        assertEquals(Resolved(NetworkFormat.TXT, Source.DEFAULT), plan().networkFormat)
        assertEquals(Resolved(NetworkFormat.HAR, Source.PROJECT), plan(project = project(config)).networkFormat)
        assertEquals(Resolved(NetworkFormat.BOTH, Source.FLAG), plan(RunOptions(networkFormat = NetworkFormat.BOTH), project(config)).networkFormat)
    }

    @Test
    fun `defaults profile de testigo yaml elige el perfil`() {
        val config = ProjectConfig(app = AppConfig(artifact = "build/app.aab"), defaults = DefaultsConfig(Profile.EVIDENCIA))

        val plan = plan(project = project(config))

        assertEquals(Resolved(Profile.EVIDENCIA, Source.PROJECT), plan.profile)
        assertEquals(Resolved(true, Source.PROFILE), plan.record)
    }

    @Test
    fun `un flag explicito gana sobre el perfil`() {
        val plan = plan(RunOptions(profile = Profile.EVIDENCIA, record = false, logs = false, network = NetworkMode.NONE))

        assertEquals(Resolved(Profile.EVIDENCIA, Source.FLAG), plan.profile)
        assertEquals(Resolved(false, Source.FLAG), plan.record)
        assertEquals(Resolved(false, Source.FLAG), plan.logs)
        assertEquals(Resolved(NetworkMode.NONE, Source.FLAG), plan.network)
    }

    @Test
    fun `network capture none en testigo yaml desactiva la red aunque el perfil la pida`() {
        val config = ProjectConfig(app = AppConfig(artifact = "build/app.aab"), network = NetworkConfig(capture = NetworkMode.NONE))

        val plan = plan(RunOptions(profile = Profile.EVIDENCIA), project(config))

        assertEquals(Resolved(NetworkMode.NONE, Source.PROJECT), plan.network)
    }

    @Test
    fun `el perfil smoke no captura red`() {
        val plan = plan(RunOptions(profile = Profile.SMOKE))

        assertEquals(Resolved(NetworkMode.NONE, Source.PROFILE), plan.network)
    }

    @Test
    fun `las rutas de testigo yaml son relativas a su carpeta y las de los flags a la carpeta actual`() {
        val cwd = tmp

        val fromProject = plan(cwd = cwd)
        val fromFlag = plan(RunOptions(app = Path.of("suite/build/app.aab")), cwd = cwd)

        assertEquals(suite.resolve("build/app.aab"), fromProject.artifact.value.path)
        assertEquals(Source.PROJECT, fromProject.artifact.source)
        assertEquals(suite.resolve("build/app.aab"), fromFlag.artifact.value.path)
        assertEquals(Source.FLAG, fromFlag.artifact.source)
        assertEquals(ArtifactFormat.AAB, fromFlag.artifact.value.format)
    }

    @Test
    fun `sin app configurada es un error`() {
        val error = assertThrows<TestigoException> { plan(project = project(ProjectConfig())) }

        assertTrue(error.message!!.contains("--app"))
    }

    @Test
    fun `una app que no es apk ni aab es un error`() {
        assertThrows<TestigoException> { plan(RunOptions(app = Path.of("build/app.zip"))) }
    }

    @Test
    fun `una app que todavia no existe es un aviso`() {
        val plan = plan(RunOptions(app = Path.of("build/otra.apk")))

        assertEquals(ArtifactFormat.APK, plan.artifact.value.format)
        assertTrue(plan.warnings.any { it.contains("todavía no existe") })
    }

    @Test
    fun `descubre flows sin subflows y filtra por tags`() {
        val all = plan()
        val smoke = plan(RunOptions(tags = listOf("smoke")))
        val withoutSmoke = plan(RunOptions(excludeTags = listOf("smoke")))

        assertEquals(2, all.flows.size)
        assertFalse(all.flows.any { it.path.toString().contains("subflows") })
        assertEquals(listOf("registro__smoke_onboarding.yaml"), smoke.flows.map { it.path.name })
        assertEquals(2, smoke.discoveredFlows.size)
        assertEquals(listOf("actualizar__perfil.yaml"), withoutSmoke.flows.map { it.path.name })
    }

    @Test
    fun `tags sin coincidencias generan un aviso`() {
        val plan = plan(RunOptions(tags = listOf("no-existe")))

        assertTrue(plan.flows.isEmpty())
        assertTrue(plan.warnings.any { it.contains("Ningún flow") })
    }

    @Test
    fun `lee env testigo por defecto y --env lo sobrescribe sin perder claves`() {
        suite.resolve(".env.testigo").writeText("# usuarios de prueba\nUSER=qa@example.com\nexport PASSWORD=\"secreto\"\n")

        val plan = plan(RunOptions(env = mapOf("USER" to "otro@example.com", "TOKEN" to "abc")))

        assertEquals(Source.DEFAULT, plan.env.fileSource)
        assertEquals(listOf("USER", "PASSWORD"), plan.env.fileKeys)
        assertEquals(listOf("USER", "TOKEN"), plan.env.inlineKeys)
        assertEquals("otro@example.com", plan.env.values["USER"])
        assertEquals("secreto", plan.env.values["PASSWORD"])
        assertFalse(plan.env.toString().contains("secreto"))
    }

    @Test
    fun `un --env-file inexistente es un error`() {
        assertThrows<TestigoException> { plan(RunOptions(envFile = Path.of("no-existe.env"))) }
    }

    @Test
    fun `sin env testigo no hay variables`() {
        val plan = plan()

        assertNull(plan.env.file)
        assertTrue(plan.env.values.isEmpty())
    }

    @Test
    fun `la evidencia va a evidence junto a testigo yaml salvo que se pase --out`() {
        val byDefault = plan(cwd = tmp)
        val byFlag = plan(RunOptions(out = Path.of("salida")), cwd = tmp)

        assertEquals(Resolved(suite.resolve("evidence"), Source.DEFAULT), byDefault.out)
        assertEquals(Resolved(tmp.resolve("salida"), Source.FLAG), byFlag.out)
    }

    @Test
    fun `la sanitizacion esta apagada por defecto y sale del flag o de testigo yaml`() {
        val enabled = ProjectConfig(app = AppConfig(artifact = "build/app.aab"), redact = RedactConfig(enabled = true))

        assertEquals(Resolved(false, Source.DEFAULT), plan().redact)
        assertEquals(Resolved(true, Source.PROJECT), plan(project = project(enabled)).redact)
        assertEquals(Resolved(false, Source.FLAG), plan(RunOptions(redact = false), project(enabled)).redact)
        assertEquals(Resolved(true, Source.FLAG), plan(RunOptions(redact = true)).redact)
    }

    @Test
    fun `sin el preset base hay un aviso si se sanitiza`() {
        val withoutBase = RedactConfig(presets = listOf(RedactPreset.PII))
        val config = ProjectConfig(app = AppConfig(artifact = "build/app.aab"), redact = withoutBase)

        assertTrue(plan(RunOptions(redact = true), project(config)).warnings.any { it.contains("no incluye base") })
        assertFalse(plan(project = project(config)).warnings.any { it.contains("no incluye base") })
        assertFalse(plan(RunOptions(redact = true)).warnings.any { it.contains("no incluye base") })
    }

    @Test
    fun `keep raw genera un aviso`() {
        val plan = plan(RunOptions(keepRaw = true))

        assertEquals(Resolved(true, Source.FLAG), plan.keepRaw)
        assertTrue(plan.warnings.any { it.contains("sin sanitizar") })
    }
}
