package testigo

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainTest {

    @Test
    fun `un flag mal escrito o testigo sin comando terminan con 2, no con el 1 de un flow fallido`() {
        assertEquals(2, exitCode(UsageError("no such option --recrod")))
        assertEquals(2, exitCode(PrintHelpMessage(null, error = true)))
    }

    @Test
    fun `help, version y los codigos del runner se conservan`() {
        assertEquals(0, exitCode(PrintHelpMessage(null)))
        assertEquals(0, exitCode(PrintMessage("testigo version 0.1.0")))
        assertEquals(4, exitCode(ProgramResult(4)))
        assertEquals(130, exitCode(ProgramResult(130)))
    }
}
