package testigo.android

import testigo.TestigoException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile

class AndroidToolsTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `lee la lista de adb devices`() {
        val output = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554          device product:sdk_gphone16k_arm64 model:sdk_gphone16k_arm64 device:emu64a16k transport_id:1
            NJ4LEYGMFQ6XJRKN       unauthorized usb:1-1 transport_id:2

        """.trimIndent()

        assertEquals(
            listOf(
                AdbDevice("emulator-5554", "device", "sdk gphone16k arm64"),
                AdbDevice("NJ4LEYGMFQ6XJRKN", "unauthorized", null),
            ),
            AdbParsers.devices(output),
        )
    }

    @Test
    fun `lee version y fecha de instalacion de dumpsys package`() {
        val dumpsys = """
            Packages:
              Package [com.empresa.app] (2f1a3b):
                userId=10234
                versionCode=70 minSdk=24 targetSdk=35
                versionName=7.0.0-qa
                firstInstallTime=2026-10-01 09:00:00
                lastUpdateTime=2026-10-16 12:34:56
              Package [com.otra.app] (9c8d7e):
                versionCode=1 minSdk=24 targetSdk=35
        """.trimIndent()

        assertEquals(
            InstalledPackage(70, "7.0.0-qa", "2026-10-16 12:34:56", uid = 10234),
            AdbParsers.installedPackage(dumpsys, "com.empresa.app"),
        )
        assertNull(AdbParsers.installedPackage("Unable to find package: com.empresa.app", "com.empresa.app"))
    }

    @Test
    fun `con un solo dispositivo listo lo elige solo`() {
        val devices = listOf(AdbDevice("emulator-5554", "device", null), AdbDevice("ABC", "offline", null))

        assertEquals("emulator-5554", DeviceSelector.select(devices, null).serial)
    }

    @Test
    fun `con varios dispositivos pide --device`() {
        val devices = listOf(AdbDevice("emulator-5554", "device", "Pixel 10"), AdbDevice("NJ4", "device", "21091116AG"))

        val error = assertThrows(TestigoException::class.java) { DeviceSelector.select(devices, null) }

        assertTrue(error.message!!.contains("--device"), error.message)
        assertTrue(error.message!!.contains("NJ4"), error.message)
        assertEquals("NJ4", DeviceSelector.select(devices, "NJ4").serial)
    }

    @Test
    fun `sin dispositivos o sin autorizar es un error claro`() {
        val none = assertThrows(TestigoException::class.java) { DeviceSelector.select(emptyList(), null) }
        val unauthorized = assertThrows(TestigoException::class.java) {
            DeviceSelector.select(listOf(AdbDevice("NJ4", "unauthorized", null)), null)
        }
        val missing = assertThrows(TestigoException::class.java) {
            DeviceSelector.select(listOf(AdbDevice("emulator-5554", "device", null)), "NJ4")
        }

        assertTrue(none.message!!.contains("No hay dispositivos"), none.message)
        assertTrue(unauthorized.message!!.contains("depuración"), unauthorized.message)
        assertTrue(missing.message!!.contains("emulator-5554"), missing.message)
    }

    @Test
    fun `usa el aapt2 de los build-tools mas nuevos`() {
        val buildTools = tmp.resolve("sdk/build-tools")
        listOf("9.0.0", "35.0.0", "36.1.0", "36.0.0").forEach {
            buildTools.resolve(it).createDirectories().resolve("aapt2").createFile()
        }
        buildTools.resolve("37.0.0-rc1").createDirectories()

        assertEquals(buildTools.resolve("36.1.0/aapt2"), AndroidSdk.latestAapt2(buildTools))
    }

    @Test
    fun `encuentra adb en ANDROID_HOME`() {
        val sdk = tmp.resolve("sdk")
        sdk.resolve("platform-tools").createDirectories().resolve("adb").createFile()

        val located = AndroidSdk.locate(env = mapOf("ANDROID_HOME" to sdk.toString()), home = tmp, osName = "Mac OS X")

        assertEquals(sdk.resolve("platform-tools/adb"), located.adb)
    }

    @Test
    fun `sin ANDROID_HOME usa la ruta por defecto de Android Studio`() {
        val sdk = tmp.resolve("Library/Android/sdk")
        sdk.resolve("platform-tools").createDirectories().resolve("adb").createFile()
        sdk.resolve("build-tools/36.1.0").createDirectories().resolve("aapt2").createFile()

        val located = AndroidSdk.locate(env = emptyMap(), home = tmp, osName = "Mac OS X")

        assertEquals(sdk.resolve("platform-tools/adb"), located.adb)
        assertEquals(sdk.resolve("build-tools/36.1.0/aapt2"), located.aapt2)
    }

    @Test
    fun `sin SDK ni adb en el PATH termina con un error claro`() {
        val error = assertThrows(TestigoException::class.java) {
            AndroidSdk.locate(env = mapOf("PATH" to tmp.toString()), home = tmp, osName = "Linux")
        }

        assertTrue(error.message!!.contains("ANDROID_HOME"), error.message)
    }
}
