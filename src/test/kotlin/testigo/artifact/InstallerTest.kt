package testigo.artifact

import testigo.TestigoException
import testigo.android.Adb
import testigo.android.DeviceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.io.path.listDirectoryEntries

class InstallerTest {

    @TempDir
    lateinit var tmp: Path

    private val device = DeviceInfo("emulator-5554", "Pixel 10", "16", 36)

    private val tools by lazy { FakeAndroidTools(tmp.resolve("sdk/adb"), tmp.resolve("jdk/bin/java")) }

    private fun installer(
        signing: AabSigning? = null,
        confirm: (String) -> Boolean = { false },
    ) = Installer(
        adb = Adb(tools.adb, tools),
        bundletool = Bundletool(tools, jar = { tmp.resolve("bundletool-all.jar") }, java = tools.java),
        aapt2 = { tmp.resolve("sdk/aapt2") },
        cacheDir = tmp.resolve("home/cache"),
        state = InstallState(tmp.resolve("home/installs.properties")),
        signing = signing,
        confirm = confirm,
        // La debug key de la máquina; la crea la primera vez, como Android Studio.
        debugKeystore = {
            tmp.resolve("home/.android/debug.keystore").also {
                if (!it.exists()) {
                    it.parent.createDirectories()
                    it.writeText("debug")
                }
            }
        },
    )

    private fun app(format: ArtifactFormat, sha256: String = "a".repeat(64)) = ArtifactInfo(
        artifact = Artifact(tmp.resolve(if (format == ArtifactFormat.APK) "app.apk" else "app.aab"), format),
        packageName = "com.empresa.app",
        versionCode = 1,
        versionName = "1.0",
        debuggable = true,
        sha256 = sha256,
    )

    @Test
    fun `un APK se instala la primera vez y no se reinstala si no cambio`() {
        val installer = installer()

        assertEquals(InstallOutcome.INSTALLED, installer.ensureInstalled(device, app(ArtifactFormat.APK)))
        assertEquals(InstallOutcome.UNCHANGED, installer.ensureInstalled(device, app(ArtifactFormat.APK)))
        assertEquals(1, tools.count("adb", "install"))
    }

    @Test
    fun `se reinstala si cambio el build aunque tenga el mismo versionCode`() {
        val installer = installer()
        installer.ensureInstalled(device, app(ArtifactFormat.APK))

        assertEquals(InstallOutcome.UPDATED, installer.ensureInstalled(device, app(ArtifactFormat.APK, sha256 = "b".repeat(64))))
        assertEquals(2, tools.count("adb", "install"))
    }

    @Test
    fun `se reinstala si alguien instalo otra cosa a mano`() {
        val installer = installer()
        installer.ensureInstalled(device, app(ArtifactFormat.APK))
        tools.installed["com.empresa.app"] = "2026-10-16 11:30:00"

        assertEquals(InstallOutcome.UPDATED, installer.ensureInstalled(device, app(ArtifactFormat.APK)))
    }

    @Test
    fun `con otra firma no desinstala sin confirmacion`() {
        tools.installed["com.empresa.app"] = "2026-10-01 09:00:00"
        tools.signatures["com.empresa.app"] = "release"

        val error = assertThrows(TestigoException::class.java) { installer().ensureInstalled(device, app(ArtifactFormat.APK)) }

        assertTrue(error.message!!.contains("adb -s emulator-5554 uninstall com.empresa.app"), error.message)
        assertEquals(0, tools.count("adb", "uninstall"))
    }

    @Test
    fun `si el telefono no deja instalar por USB dice que activar`() {
        tools.userRestricted = true

        val error = assertThrows(TestigoException::class.java) { installer().ensureInstalled(device, app(ArtifactFormat.APK)) }

        assertTrue(error.message!!.contains("INSTALL_FAILED_USER_RESTRICTED"), error.message)
        assertTrue(error.message!!.contains("«Instalar vía USB»"), error.message)
    }

    @Test
    fun `con otra firma y confirmacion desinstala e instala`() {
        tools.installed["com.empresa.app"] = "2026-10-01 09:00:00"
        tools.signatures["com.empresa.app"] = "release"

        val outcome = installer(confirm = { true }).ensureInstalled(device, app(ArtifactFormat.APK))

        assertEquals(InstallOutcome.REINSTALLED, outcome)
        assertEquals(1, tools.count("adb", "uninstall"))
    }

    @Test
    fun `un AAB genera los APKs una sola vez`() {
        val installer = installer()

        assertEquals(InstallOutcome.INSTALLED, installer.ensureInstalled(device, app(ArtifactFormat.AAB)))
        assertEquals(InstallOutcome.UNCHANGED, installer.ensureInstalled(device, app(ArtifactFormat.AAB)))
        tools.installed["com.empresa.app"] = "2026-10-16 11:30:00"
        assertEquals(InstallOutcome.UPDATED, installer.ensureInstalled(device, app(ArtifactFormat.AAB)))

        assertEquals(1, tools.count("bundletool", "build-apks"), "el set de APKs sale de la caché")
        assertEquals(2, tools.count("bundletool", "install-apks"))
        assertEquals(1, tmp.resolve("home/cache/apks").listDirectoryEntries("*.apks").size)
    }

    @Test
    fun `un AAB sin app signing ni debug key avisa antes de generar APKs sin firma`() {
        val installer = Installer(
            adb = Adb(tools.adb, tools),
            bundletool = Bundletool(tools, jar = { tmp.resolve("bundletool-all.jar") }, java = tools.java),
            aapt2 = { tmp.resolve("sdk/aapt2") },
            cacheDir = tmp.resolve("home/cache"),
            state = InstallState(tmp.resolve("home/installs.properties")),
            signing = null,
            confirm = { false },
            debugKeystore = { null },
        )

        val error = assertThrows(TestigoException::class.java) { installer.ensureInstalled(device, app(ArtifactFormat.AAB)) }

        assertTrue(error.message!!.contains("keytool -genkeypair -keystore ~/.android/debug.keystore"), error.message)
        assertEquals(0, tools.count("bundletool", "build-apks"), "no genera ni guarda en caché APKs sin firma")
    }

    @Test
    fun `si cambia la debug key se vuelven a generar los APKs`() {
        installer().ensureInstalled(device, app(ArtifactFormat.AAB))
        tmp.resolve("home/.android/debug.keystore").writeText("otra debug key")
        tools.installed["com.empresa.app"] = "2026-10-17 09:00:00"

        installer().ensureInstalled(device, app(ArtifactFormat.AAB))

        assertEquals(2, tools.count("bundletool", "build-apks"))
    }

    @Test
    fun `la clave del keystore nunca va en la linea de comandos`() {
        val signing = AabSigning(tmp.resolve("release.jks"), "upload", "clave-super-secreta")

        installer(signing = signing).ensureInstalled(device, app(ArtifactFormat.AAB))

        val buildApks = tools.commands.single { it.getOrNull(3) == "build-apks" }
        assertFalse(buildApks.any { "clave-super-secreta" in it }, buildApks.toString())
        val passwordFile = Path.of(buildApks.first { it.startsWith("--ks-pass=file:") }.removePrefix("--ks-pass=file:"))
        assertFalse(passwordFile.exists(), "el archivo temporal con la clave se borra")
        assertFalse(signing.toString().contains("clave-super-secreta"))
    }
}
