package testigo.artifact

import testigo.process.CommandResult
import testigo.process.CommandRunner
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration

/**
 * Simula adb y bundletool con un solo dispositivo: qué apps tiene, con qué firma y cuándo se instalaron.
 * Guarda cada comando para que los tests vean qué se ejecutó.
 */
class FakeAndroidTools(val adb: Path, val java: Path) : CommandRunner {
    val commands = mutableListOf<List<String>>()

    /** package → lastUpdateTime */
    val installed = mutableMapOf<String, String>()

    /** package → firma con la que está instalado */
    val signatures = mutableMapOf<String, String>()

    /** Firma del artefacto que se instale a continuación. */
    var artifactSignature = "debug"

    /** Package que contiene el artefacto que se instale a continuación. */
    var artifactPackage = "com.empresa.app"

    /** El teléfono pide confirmar la instalación y nadie toca Instalar (MIUI sin «Instalar vía USB»). */
    var userRestricted = false

    private var clock = 0

    fun count(tool: String, subcommand: String): Int = commands.count { command ->
        when (tool) {
            "adb" -> command.first() == adb.toString() && subcommand in command
            else -> command.first() == java.toString() && command.getOrNull(3) == subcommand
        }
    }

    override fun run(command: List<String>, timeout: Duration): CommandResult {
        commands += command
        return when (command.first()) {
            adb.toString() -> adb(command.drop(1).let { if (it.firstOrNull() == "-s") it.drop(2) else it })
            java.toString() -> bundletool(command.drop(3))
            else -> error("comando inesperado: $command")
        }
    }

    private fun adb(args: List<String>): CommandResult = when (args.first()) {
        "devices" -> ok("List of devices attached\nemulator-5554  device product:x model:Pixel_10 device:y\n")
        "shell" -> shell(args[1])
        "install" -> install()
        "uninstall" -> {
            installed.remove(args[1])
            signatures.remove(args[1])
            ok("Success")
        }
        else -> error("adb inesperado: $args")
    }

    private fun shell(command: String): CommandResult = when {
        command.startsWith("getprop") -> ok("Pixel 10\n16\n36\n")
        command.startsWith("dumpsys package ") -> {
            val packageName = command.removePrefix("dumpsys package ")
            val time = installed[packageName]
            if (time == null) {
                ok("Unable to find package: $packageName\n")
            } else {
                ok("Packages:\n  Package [$packageName] (abc):\n    versionCode=1 minSdk=24\n    versionName=1.0\n    lastUpdateTime=$time\n")
            }
        }
        else -> error("shell inesperado: $command")
    }

    private fun install(): CommandResult {
        if (userRestricted) {
            return CommandResult(1, "", "adb: failed to install app.apk: Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]")
        }
        val current = signatures[artifactPackage]
        if (current != null && current != artifactSignature) {
            return CommandResult(
                1,
                "",
                "adb: failed to install app.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package $artifactPackage signatures do not match]",
            )
        }
        installed[artifactPackage] = "2026-10-16 10:00:%02d".format(clock++)
        signatures[artifactPackage] = artifactSignature
        return ok("Performing Streamed Install\nSuccess\n")
    }

    private fun bundletool(args: List<String>): CommandResult {
        fun flag(name: String) = args.first { it.startsWith("--$name=") }.substringAfter('=')
        return when (args.first()) {
            "get-device-spec" -> {
                Path.of(flag("output")).writeText("{\"supportedAbis\":[\"arm64-v8a\"],\"sdkVersion\":36}")
                ok("")
            }
            "build-apks" -> {
                Path.of(flag("output")).writeText("apks")
                ok("")
            }
            "install-apks" -> install().let { if (it.ok) ok("") else CommandResult(1, "", "Error: ${it.stderr}") }
            "dump" -> ok("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$artifactPackage\"/>")
            else -> error("bundletool inesperado: $args")
        }
    }

    private fun ok(stdout: String) = CommandResult(0, stdout, "")
}
