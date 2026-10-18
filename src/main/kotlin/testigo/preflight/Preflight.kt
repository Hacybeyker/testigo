package testigo.preflight

import testigo.TestigoException
import testigo.android.Adb
import testigo.android.AndroidSdk
import testigo.android.DeviceInfo
import testigo.android.DeviceSelector
import testigo.artifact.AabSigning
import testigo.artifact.ArtifactFormat
import testigo.artifact.ArtifactInfo
import testigo.artifact.ArtifactInspector
import testigo.artifact.Bundletool
import testigo.artifact.InstallOutcome
import testigo.artifact.InstallState
import testigo.artifact.Installer
import testigo.config.NetworkMode
import testigo.config.RunPlan
import testigo.process.CommandRunner
import testigo.util.displayFrom
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

data class PreflightResult(
    val adb: Path,
    val device: DeviceInfo,
    val app: ArtifactInfo,
    /** uid de la app en el dispositivo, para filtrar logcat. */
    val appUid: Int?,
    val install: InstallOutcome,
    val warnings: List<String>,
)

/** Carpeta del runner en la máquina del dev: estado de instalaciones y caché de APKs generados desde AAB. */
object TestigoHome {
    fun dir(env: Map<String, String> = System.getenv()): Path =
        env["TESTIGO_HOME"]?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".testigo")
}

/**
 * Paso 3 del ciclo de una corrida: dispositivo, artefacto e instalación.
 * Cualquier problema termina con código 2 (entorno), antes de ejecutar un solo flow.
 */
class Preflight(
    private val runner: CommandRunner,
    private val sdk: () -> AndroidSdk,
    private val home: Path,
    private val bundletool: Bundletool = Bundletool(runner),
    private val confirm: (String) -> Boolean,
    private val progress: (String) -> Unit = {},
) {
    /** Lo primero que se revisa: sin app no tiene sentido buscar Maestro ni tocar el dispositivo. */
    fun checkArtifact(plan: RunPlan) {
        val path = plan.artifact.value.path
        if (!path.isRegularFile()) {
            throw TestigoException("La app no existe: ${path.displayFrom(plan.cwd)}. Genera el build o corrige app.artifact.")
        }
    }

    fun run(plan: RunPlan): PreflightResult {
        checkArtifact(plan)
        val artifact = plan.artifact.value

        val sdk = sdk()
        val adb = Adb(sdk.adb, runner)
        val selected = DeviceSelector.select(adb.devices(), plan.device.value)
        val device = adb.deviceInfo(selected.serial)
        progress("Dispositivo    ${device.label}")

        val app = ArtifactInspector(runner, { sdk.aapt2 }, bundletool).inspect(artifact)
        val expected = plan.project.config.app.packageName
        if (expected != "auto" && expected != app.packageName) {
            throw TestigoException(
                "El artefacto es ${app.packageName}, pero testigo.yaml dice app.package: $expected. Corrige uno de los dos.",
            )
        }
        progress("App            ${app.label} · sha256 ${app.sha256.take(12)}")

        val installer = Installer(
            adb = adb,
            bundletool = bundletool,
            aapt2 = { sdk.aapt2 },
            cacheDir = home.resolve("cache"),
            state = InstallState(home.resolve("installs.properties")),
            signing = if (artifact.format == ArtifactFormat.AAB) signing(plan) else null,
            confirm = confirm,
            progress = { progress("               $it") },
        )
        val outcome = installer.ensureInstalled(device, app)
        progress("Instalación    ${outcome.label}")
        val uid = adb.installedPackage(device.serial, app.packageName)?.uid

        return PreflightResult(sdk.adb, device, app, uid, outcome, warnings(plan, app))
    }

    private fun signing(plan: RunPlan): AabSigning? {
        val config = plan.project.config.app.signing ?: return null
        val keystore = plan.project.dir.resolve(config.keystore).normalize()
        if (!keystore.exists()) throw TestigoException("No existe el keystore de app.signing: ${keystore.displayFrom(plan.cwd)}")
        val password = plan.env.values[config.passwordEnv] ?: System.getenv(config.passwordEnv)
            ?: throw TestigoException(
                "Falta la variable ${config.passwordEnv} con la clave del keystore: agrégala a .env.testigo o a tu entorno.",
            )
        return AabSigning(keystore, config.alias, password)
    }

    private fun warnings(plan: RunPlan, app: ArtifactInfo): List<String> = buildList {
        if (!app.debuggable && plan.network.value == NetworkMode.CHUCKER) {
            add("${app.packageName} no es debuggable: la base de Chucker solo se podrá leer si adbd corre como root (adb root en un emulador).")
        }
        plan.flows
            .filter { flow -> flow.header.appId.let { it != null && "\${" !in it && it != app.packageName } }
            .forEach { flow ->
                add("${flow.path.displayFrom(plan.cwd)} usa appId ${flow.header.appId}, distinto de la app instalada (${app.packageName}).")
            }
    }
}
