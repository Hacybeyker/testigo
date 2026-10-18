package testigo.android

import testigo.TestigoException
import testigo.process.CommandResult
import testigo.process.CommandRunner
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Un dispositivo según `adb devices -l`. [state] es `device` cuando está listo. */
data class AdbDevice(val serial: String, val state: String, val model: String?) {
    val ready: Boolean get() = state == "device"
}

data class DeviceInfo(val serial: String, val model: String, val androidRelease: String, val sdk: Int?) {
    /** `emulator-5554 · Pixel 10 Pro · Android 16 (API 36)` */
    val label: String
        get() = listOf(serial, model, "Android $androidRelease" + (sdk?.let { " (API $it)" } ?: "")).joinToString(" · ")
}

/** Lo que el dispositivo dice de una app instalada. [uid] permite filtrar logcat a la app. */
data class InstalledPackage(val versionCode: Long?, val versionName: String?, val lastUpdateTime: String?, val uid: Int? = null)

/** Las operaciones de adb que necesita el runner, siempre contra un serial concreto (`-s`). */
class Adb(private val adb: Path, private val runner: CommandRunner) {

    val path: Path get() = adb

    fun devices(): List<AdbDevice> {
        val result = run(listOf("devices", "-l"), 30.seconds)
        if (!result.ok) throw TestigoException("adb devices falló: ${result.output}")
        return AdbParsers.devices(result.stdout)
    }

    fun deviceInfo(serial: String): DeviceInfo {
        val result = shell(serial, "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk")
        val lines = result.stdout.lines().map { it.trim() }
        return DeviceInfo(
            serial = serial,
            model = lines.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "modelo desconocido",
            androidRelease = lines.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "?",
            sdk = lines.getOrNull(2)?.toIntOrNull(),
        )
    }

    /** null si la app no está instalada. */
    fun installedPackage(serial: String, packageName: String): InstalledPackage? =
        AdbParsers.installedPackage(shell(serial, "dumpsys package $packageName").stdout, packageName)

    /** `-r` reemplaza, `-t` acepta builds de prueba y `-d` permite bajar de versión, algo común entre builds de QA. */
    fun install(serial: String, apk: Path): CommandResult =
        run(listOf("-s", serial, "install", "-r", "-t", "-d", apk.toString()), 5.minutes)

    fun uninstall(serial: String, packageName: String): CommandResult =
        run(listOf("-s", serial, "uninstall", packageName), 2.minutes)

    fun shell(serial: String, command: String, timeout: Duration = 30.seconds): CommandResult =
        run(listOf("-s", serial, "shell", command), timeout)

    private fun run(args: List<String>, timeout: Duration): CommandResult = runner.run(listOf(adb.toString()) + args, timeout)
}

internal object AdbParsers {
    fun devices(output: String): List<AdbDevice> =
        output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val model = parts.firstOrNull { it.startsWith("model:") }?.removePrefix("model:")?.replace('_', ' ')
                AdbDevice(parts[0], parts[1], model)
            }

    fun installedPackage(dumpsys: String, packageName: String): InstalledPackage? {
        val start = dumpsys.indexOf("Package [$packageName]")
        if (start < 0) return null
        val section = dumpsys.substring(start)
        fun value(key: String): String? =
            Regex("(?m)^\\s*$key=([^\\n]*?)(\\s+\\w+=.*)?$").find(section)?.groupValues?.get(1)?.trim()
        return InstalledPackage(
            versionCode = value("versionCode")?.toLongOrNull(),
            versionName = value("versionName"),
            lastUpdateTime = value("lastUpdateTime"),
            uid = (value("userId") ?: value("appId"))?.toIntOrNull(),
        )
    }
}
