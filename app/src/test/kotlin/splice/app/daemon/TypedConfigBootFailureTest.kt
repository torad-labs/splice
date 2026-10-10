// A splice.toml value of the wrong type stops the boot with every finding listed, each with its fix, not an UNCAUGHT report with
// frames. The boot is the real one (a splice.toml on disk, DaemonProcess.prepare); unknown failures keep their frames.
package splice.app.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.app.DaemonProcess
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.TopologyRefusal
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private const val PLANTED = "918273645"
private const val FRAME = "    at "
private const val TRACE_SENTENCE = "heads.ex.overrides.trace (line 17): expects quoted string"
private const val TRACE_FIX = "put the value in double quotes, as in trace = \"...\""
private const val HEADER_SENTENCE = "providers.ex.extra_headers.x-api-key (line 17): expects quoted string"

private val HEAD = """
    [providers.ex]
    dialect = "openai-chat"
    base_url = "https://api.example.com/v1"
    auth = { kind = "api-key", env = "EX_KEY" }

    [[providers.ex.models]]
    id = "m1"
    context_window = 128000

    [heads.ex]
    provider = "ex"
    port = 8801
    discovery_prefix = "ex/"
    pinned_model = "m1"
""".trimIndent() + "\n"

class TypedConfigBootFailureTest {

    @TempDir
    lateinit var tmp: Path

    private val statePaths by lazy { StatePaths(baseOverride = tmp.resolve("state")) }

    private fun boot(extra: String): Throwable {
        val config = tmp.resolve("splice.toml")
        Files.writeString(config, HEAD + extra)
        return assertThrows<Throwable> {
            DaemonProcess().prepare(EnvReader { if (it == "SPLICE_CONFIG") config.toString() else null })
        }
    }

    /** What the handler main installs writes to stderr and daemon.log for [failure]. */
    private fun report(failure: Throwable): Pair<String, String> {
        val err = ByteArrayOutputStream()
        val real = System.err
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            DaemonProcess().bootFailureHandler(statePaths).uncaughtException(Thread.currentThread(), failure)
        } finally {
            System.setErr(real)
        }
        return err.toString(Charsets.UTF_8) to Files.readString(statePaths.logsDir.resolve("daemon.log"))
    }

    private fun assertOneSentence(shown: String, where: String) {
        assertTrue(shown.contains("problem(s); splice will not start until all are fixed"), "$where: the list: $shown")
        assertFalse(shown.contains("UNCAUGHT"), "$where: a refusal is not an uncaught crash: $shown")
        assertFalse(shown.contains(FRAME), "$where: no stack frame: $shown")
        assertFalse(shown.contains(PLANTED), "$where: the operator's value never appears: $shown")
    }

    @Test
    fun `an unquoted trace override stops the boot with the sentence and how to fix it`() {
        val failure = boot("\n[heads.ex.overrides]\ntrace = true\n")
        assertTrue(failure is TopologyRefusal, "the loader's refusal reaches the handler: $failure")
        val (stderr, log) = report(failure)
        for ((where, shown) in listOf("stderr" to stderr, "daemon.log" to log)) {
            assertOneSentence(shown, where)
            assertTrue(shown.contains(TRACE_SENTENCE), "$where: $shown")
            assertTrue(shown.contains(TRACE_FIX), "$where: $shown")
        }
        assertEquals(stderr, log, "stderr and daemon.log say the same thing")
    }

    @Test
    fun `a wrong-typed header value is named by key and line and its value stays out`() {
        val failure = boot("\n[providers.ex.extra_headers]\nx-api-key = $PLANTED\n")
        val (stderr, log) = report(failure)
        for ((where, shown) in listOf("stderr" to stderr, "daemon.log" to log)) {
            assertOneSentence(shown, where)
            assertTrue(shown.contains(HEADER_SENTENCE), "$where: $shown")
        }
    }

    @Test
    fun `a boolean where a number belongs says what to write instead`() {
        val failure = boot("\n[daemon]\ncontrol_port = true\n")
        val (stderr, _) = report(failure)
        assertOneSentence(stderr, "stderr")
        assertTrue(stderr.contains("daemon.control_port (line 17): expects integer"), stderr)
        assertTrue(stderr.contains("write a whole number, with no quotes"), stderr)
    }

    @Test
    fun `an unknown boot failure still prints UNCAUGHT with its frames`() {
        val (stderr, log) = report(RuntimeException("planted $PLANTED"))
        for ((where, shown) in listOf("stderr" to stderr, "daemon.log" to log)) {
            assertTrue(shown.contains("UNCAUGHT on ${Thread.currentThread().name}"), "$where: $shown")
            assertTrue(shown.contains(FRAME), "$where: an unknown failure keeps its frames: $shown")
            assertFalse(shown.contains(PLANTED), "$where: the message stays out: $shown")
        }
    }
}
