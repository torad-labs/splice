// NEW: V4-366 — a splice.toml value of the wrong type stops the boot with one sentence and its fix, not
// "[daemon] UNCAUGHT on main:" and seven frames. Found by the V4-355 live check: the typed diagnostic
// ('splice.toml: heads.local.overrides.trace at line 17 expects quoted string') reached the uncaught
// handler, which printed the frames of a failure that is the operator's own line, and no fix. The boot
// here is the real one: a splice.toml on disk, DaemonProcess.prepare, and whatever it throws is handed to
// the handler main installs. Unknown failures keep DR-170's frames (DaemonBootFailureTest pins the rest).
package splice.app.v4366

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.app.DaemonProcess
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.TopologyTypeFailure
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private const val PLANTED = "918273645"
private const val FRAME = "    at "
private const val TRACE_SENTENCE = "splice.toml: heads.ex.overrides.trace at line 17 expects quoted string"
private const val TRACE_FIX = "Fix: put the value in double quotes, as in trace = \"...\""
private const val HEADER_SENTENCE = "providers.ex.extra_headers.x-api-key at line 17 expects quoted string"

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

class TypedBootFailureTest {

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
        assertEquals(1, shown.lines().count { it.isNotBlank() }, "$where: one line, no frames: $shown")
        assertFalse(shown.contains("UNCAUGHT"), "$where: a typed diagnostic is not an uncaught crash: $shown")
        assertFalse(shown.contains(FRAME), "$where: no stack frame: $shown")
        assertFalse(shown.contains(PLANTED), "$where: the operator's value never appears: $shown")
    }

    @Test
    fun `an unquoted trace override stops the boot with the sentence and how to fix it`() {
        val failure = boot("\n[heads.ex.overrides]\ntrace = true\n")
        assertTrue(failure is TopologyTypeFailure, "the loader's typed diagnostic reaches the handler: $failure")
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
        assertTrue(stderr.contains("daemon.control_port at line 17 expects integer"), stderr)
        assertTrue(stderr.contains("Fix: write a whole number, with no quotes"), stderr)
    }

    @Test
    fun `an unknown boot failure still prints UNCAUGHT with its frames`() {
        val (stderr, log) = report(RuntimeException("planted $PLANTED"))
        for ((where, shown) in listOf("stderr" to stderr, "daemon.log" to log)) {
            assertTrue(shown.contains("UNCAUGHT on ${Thread.currentThread().name}"), "$where: $shown")
            assertTrue(shown.contains(FRAME), "$where: DR-170 keeps the frames: $shown")
            assertFalse(shown.contains(PLANTED), "$where: DR-65 keeps the message out: $shown")
        }
    }
}
