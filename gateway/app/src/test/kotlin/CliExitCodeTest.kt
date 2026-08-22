import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.app.cli.Cli
import splice.app.cli.Command

class CliExitCodeTest {

    @Test
    fun `unknown commands return usage exit code`() {
        assertEquals(2, Cli().runCli(arrayOf("definitely-not-a-command")))
    }

    @Test
    fun `failed command outcomes return nonzero`() {
        // outcomeExitCode moved from a top-level function onto `Command` itself; every case
        // inherits the same mapping, so the receiver here is arbitrary.
        assertEquals(1, Command.Version.outcomeExitCode(false))
        assertEquals(0, Command.Version.outcomeExitCode(true))
    }
}
