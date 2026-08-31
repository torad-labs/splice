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

    // DR-99: the CLI failure boundary. A malformed splice.toml escaped status/login/install/etc.
    // as a raw TomlDecodingException stack trace, and ktoml decode text can quote the offending
    // config line — which legally carries credential-like extra_headers values (the DR-92 class).
    // ktoml's TomlDecodingException extends SerializationException, so the secret-bearing arm
    // throws that supertype with the sentinel in the message; SafeFailureText must withhold it.
    private fun stderrOf(body: () -> Unit): String {
        val err = java.io.ByteArrayOutputStream()
        val saved = System.err
        System.setErr(java.io.PrintStream(err))
        try {
            body()
        } finally {
            System.setErr(saved)
        }
        return err.toString()
    }

    @Test
    fun `a decode failure renders one safe line - no secret, no stack trace - DR-99`() {
        var code = -1
        val err = stderrOf {
            code = Cli().guarded {
                throw kotlinx.serialization.SerializationException("""line 7: x-api-key = "sk-SENT-99"""")
            }
        }
        assertEquals(1, code)
        org.junit.jupiter.api.Assertions.assertTrue(err.startsWith("splice: "), err)
        org.junit.jupiter.api.Assertions.assertFalse(err.contains("sk-SENT-99"), "config bytes must be withheld: $err")
        org.junit.jupiter.api.Assertions.assertFalse(err.contains("\tat "), "no stack trace on the boundary: $err")
    }

    @Test
    fun `a missing config file renders its path and exits nonzero - DR-99`() {
        var code = -1
        val err = stderrOf {
            code = Cli().guarded { throw java.nio.file.NoSuchFileException("/tmp/splice-none/splice.toml") }
        }
        assertEquals(1, code)
        org.junit.jupiter.api.Assertions.assertTrue(
            err.contains("/tmp/splice-none/splice.toml"),
            "FileSystemException keeps its path — the useful safe diagnostic: $err",
        )
    }

    @Test
    fun `the boundary passes successes through and lets cancellation escape - DR-99`() {
        assertEquals(0, Cli().guarded { 0 })
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException::class.java) {
            Cli().guarded { throw java.util.concurrent.CancellationException("turn cancelled") }
        }
    }
}
