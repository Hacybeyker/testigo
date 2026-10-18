package testigo

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import testigo.cli.DoctorCommand
import testigo.cli.TestigoCommand
import testigo.cli.InitCommand
import testigo.cli.ListCommand
import testigo.cli.RunCommand
import testigo.cli.SetupCommand
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // En el orden en que se usan: preparar la máquina, crear la suite, revisar, ver los flows y ejecutar.
    val command = TestigoCommand().subcommands(SetupCommand(), InitCommand(), DoctorCommand(), ListCommand(), RunCommand())
    // Lo mismo que hace `main()` de Clikt, salvo los códigos de salida (ver exitCode) y los errores fuera de Clikt.
    try {
        command.parse(args)
    } catch (e: CliktError) {
        command.echoFormattedHelp(e)
        exitProcess(exitCode(e))
    } catch (e: TestigoException) {
        // Un problema esperado (adb que no responde, un archivo que falta) en medio de un comando: sin stacktrace.
        System.err.println("Error: ${e.message}")
        exitProcess(e.exitCode.code)
    } catch (e: Exception) {
        System.err.println("Error inesperado del runner (no es un fallo de tus flows): $e")
        e.printStackTrace()
        exitProcess(ExitCode.ENVIRONMENT.code)
    }
}

/**
 * Clikt termina con 1 ante un flag mal escrito, pero en el runner 1 significa "un flow falló". Un error de uso
 * (o `testigo` sin comando) es un error de configuración: 2. El resto (`--help`, `--version`, [com.github.ajalt.clikt.core.ProgramResult])
 * conserva su código.
 */
internal fun exitCode(e: CliktError): Int = when {
    e is UsageError -> ExitCode.ENVIRONMENT.code
    e is PrintHelpMessage && e.error -> ExitCode.ENVIRONMENT.code
    else -> e.statusCode
}
