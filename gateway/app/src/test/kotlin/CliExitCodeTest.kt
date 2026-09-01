import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TopologyLoader
import splice.app.cli.Cli
import splice.app.cli.Command
import java.nio.file.Files
import java.nio.file.Path

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

    /** DR-99 (coverage redo, review 2026-08-31): every arm above enters at `guarded`, so all of
     *  them stay GREEN if `runCli` regresses to `return command.run()` — the boundary being
     *  BYPASSED is the defect, and a helper-only arm cannot see it. This one enters where the
     *  process does: argv in, exit code out, over a real verb (`status` loads the topology on its
     *  first line) and a real malformed splice.toml on the real resolution path.
     *
     *  The fixture's broken construct is an `extra_headers` string where the schema wants a table,
     *  carrying a sentinel: that is the DR-92 shape this boundary exists for — ktoml decode text
     *  can quote the offending config line, and the line legally holds credential-like values. So
     *  the arm pins both halves at once: the boundary catches (exit 1, one line, no stack trace)
     *  AND the rendered line withholds the bytes. */
    @Test
    fun `runCli routes a real verb's config failure through the boundary - DR-99`(@TempDir tmp: Path) {
        val config = tmp.resolve(".config").resolve("splice").resolve("splice.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, MALFORMED_CONFIG_TOML)
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.toString())
        var code = -1
        try {
            // PREMISE, asserted not assumed: an ambient SPLICE_CONFIG / XDG_CONFIG_HOME would aim
            // the verb at the operator's own config and make every assertion below vacuous.
            assertEquals(
                config,
                TopologyLoader.configPath(),
                "the redirected config path must be the one the verb reads",
            )
            val err = stderrOf { code = Cli().runCli(arrayOf("status")) }
            assertEquals(1, code, "a malformed config must exit nonzero THROUGH runCli, not escape it")
            assertTrue(err.startsWith("splice: "), "one safe line from the boundary, was: $err")
            assertFalse(err.contains("\tat "), "no stack trace may reach the operator: $err")
            assertFalse(err.contains(CONFIG_SENTINEL), "config bytes must be withheld (DR-92/DR-65): $err")
        } finally {
            System.setProperty("user.home", savedHome)
        }
    }
}

/** A sentinel that looks like what `extra_headers` legally carries, on the very construct whose
 *  decode fails — so a renderer that quotes the offending line would leak it. */
private const val CONFIG_SENTINEL = "sk-PROD-99-SENTINEL"

/** `extra_headers` is `Map<String, String>`; a bare string is a decode-type failure, which is the
 *  malformed-config shape DR-99 was opened against (a raw TomlDecodingException stack trace). */
private val MALFORMED_CONFIG_TOML = """
[daemon]
control_port = 3096

[providers.openrouter]
dialect = "openai-chat"
base_url = "https://example.invalid"
auth = { kind = "api-key" }
extra_headers = "$CONFIG_SENTINEL"

[heads.fast]
provider = "openrouter"
port = 3101
discovery_prefix = "claude-fast--"
pinned_model = "m"
"""
