import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.app.cli.outcomeExitCode
import splice.app.cli.runCli

class CliExitCodeTest {

    @Test
    fun `unknown commands return usage exit code`() {
        assertEquals(2, runCli(arrayOf("definitely-not-a-command")))
    }

    @Test
    fun `failed command outcomes return nonzero`() {
        assertEquals(1, outcomeExitCode(false))
        assertEquals(0, outcomeExitCode(true))
    }
}
