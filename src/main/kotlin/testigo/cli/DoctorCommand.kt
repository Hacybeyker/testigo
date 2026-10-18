package testigo.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import testigo.ExitCode
import testigo.config.ConfigLoader
import testigo.doctor.Doctor
import testigo.maestro.YamlFlowHeaderReader
import java.nio.file.Path

class DoctorCommand : CliktCommand(name = "doctor") {

    override fun help(context: Context): String =
        "Revisa que todo esté listo para testigo run: Java, Maestro y su versión, adb, aapt2, bundletool, el dispositivo " +
            "y, con testigo.yaml, los flows, el build, la app instalada, run-as y la base de Chucker. No instala ni cambia nada. " +
            "Termina con código 2 si encuentra un problema."

    private val config by option("--config", help = "Ruta a testigo.yaml. Por defecto se busca desde la carpeta actual hacia arriba.")
        .path()
    private val device by option("--device", help = "Serial de adb del dispositivo. Por defecto, el único conectado.")

    override fun run() {
        val cwd = Path.of("").toAbsolutePath().normalize()
        echo(DoctorRenderer.HEADER)
        val report = Doctor(
            loadProject = { ConfigLoader.load(config, cwd) },
            device = device,
            headerReader = YamlFlowHeaderReader(),
            cwd = cwd,
        ).run { section -> echo(DoctorRenderer.section(section), trailingNewline = false) }
        echo()
        echo(DoctorRenderer.summary(report))
        if (report.exitCode != ExitCode.OK) throw ProgramResult(report.exitCode.code)
    }
}
