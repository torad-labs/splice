// NEW (JW-08): the `splice logs` verb — daemon-independent (pure LogFileSource read), head
// filtering, tail bounding, missing-file tolerance. Every remediation path ends at daemon.log;
// before this there was no CLI verb to reach it.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.LogsCommand
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class LogsCommandTest {

    private fun capture(env: Map<String, String?>, args: List<String>): Pair<Boolean, String> {
        val buf = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buf, true))
        return try {
            LogsCommand().logs(args) { env[it] } to buf.toString()
        } finally {
            System.setOut(original)
        }
    }

    private fun stateEnv(tmp: Path): Map<String, String?> {
        val state = Files.createDirectories(tmp.resolve("state"))
        Files.createDirectories(state.parent.resolve("logs"))
        return mapOf("CLAUDEX_STATE_DIR" to state.toString())
    }

    private fun writeLog(tmp: Path, vararg lines: String) {
        val logs = Files.createDirectories(tmp.resolve("logs"))
        Files.writeString(logs.resolve("daemon.log"), lines.joinToString("") { "$it\n" })
    }

    @Test
    fun `tail returns the last N lines`(@TempDir tmp: Path) {
        val env = stateEnv(tmp)
        writeLog(tmp, *(1..10).map { "[claudex] line $it" }.toTypedArray())
        val (ok, out) = capture(env, listOf("--tail", "3"))
        assertTrue(ok)
        assertEquals(listOf("[claudex] line 8", "[claudex] line 9", "[claudex] line 10"), out.trim().lines())
    }

    @Test
    fun `head filter restricts to one head's lines - JW-08`(@TempDir tmp: Path) {
        val env = stateEnv(tmp)
        writeLog(tmp, "[claudex] mine", "[claude-grok] theirs", "[claudex] mine too")
        val (ok, out) = capture(env, listOf("--head", "claudex"))
        assertTrue(ok)
        assertEquals(listOf("[claudex] mine", "[claudex] mine too"), out.trim().lines())
    }

    @Test
    fun `a missing daemon_log is empty and exit-0, never an error`(@TempDir tmp: Path) {
        val env = stateEnv(tmp) // logs dir exists but no daemon.log written
        val (ok, out) = capture(env, emptyList())
        assertTrue(ok, "a fresh install with no logs is not a failure")
        assertEquals("", out)
    }

    @Test
    fun `an unknown option fails with usage`(@TempDir tmp: Path) {
        val (ok, _) = capture(stateEnv(tmp), listOf("--bogus"))
        assertTrue(!ok)
    }
}

// DR-100: --follow must print the DELTA, not the 20-line tail snapshot — the snapshot repeated
// up to 19 already-shown lines per new line and silently dropped any burst over 20 lines inside
// one poll (exactly the error-storm lines being watched). Drives the extracted followPoll step.
class LogsFollowDeltaTest {

    private fun pollCapture(block: () -> Long): Pair<Long, String> {
        val buf = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(buf, true))
        return try {
            block() to buf.toString()
        } finally {
            System.setOut(original)
        }
    }

    private fun warned() = java.util.concurrent.atomic.AtomicBoolean(false)

    @org.junit.jupiter.api.Test
    fun `a follow poll prints each new line exactly once - DR-100`(@TempDir tmp: Path) {
        val log = tmp.resolve("daemon.log")
        Files.writeString(log, "old 1\nold 2\nold 3\n")
        val baseline = Files.size(log)
        Files.writeString(log, "new 1\nnew 2\n", java.nio.file.StandardOpenOption.APPEND)
        val (next, out) = pollCapture {
            LogsCommand().followPoll(log, splice.app.LogFileSource(log), baseline, warned())
        }
        assertEquals(listOf("new 1", "new 2"), out.trim().lines(), "already-shown lines must not repeat")
        assertEquals(Files.size(log), next)
    }

    @org.junit.jupiter.api.Test
    fun `a 25-line burst inside one poll surfaces all 25 lines - DR-100`(@TempDir tmp: Path) {
        val log = tmp.resolve("daemon.log")
        Files.writeString(log, "seed\n")
        val baseline = Files.size(log)
        val burst = (1..25).joinToString("") { "storm $it\n" }
        Files.writeString(log, burst, java.nio.file.StandardOpenOption.APPEND)
        val (_, out) = pollCapture {
            LogsCommand().followPoll(log, splice.app.LogFileSource(log), baseline, warned())
        }
        assertEquals((1..25).map { "storm $it" }, out.trim().lines(), "no line of the burst may drop")
    }

    @org.junit.jupiter.api.Test
    fun `a torn final line waits for its newline instead of printing half - DR-100`(@TempDir tmp: Path) {
        val log = tmp.resolve("daemon.log")
        Files.writeString(log, "seed\n")
        val baseline = Files.size(log)
        Files.writeString(log, "torn-half", java.nio.file.StandardOpenOption.APPEND)
        val cmd = LogsCommand()
        val (afterTorn, tornOut) = pollCapture {
            cmd.followPoll(log, splice.app.LogFileSource(log), baseline, warned())
        }
        assertEquals("", tornOut.trim(), "an incomplete line must not print")
        assertEquals(baseline, afterTorn, "the baseline must not advance past unprinted bytes")
        Files.writeString(log, "-done\n", java.nio.file.StandardOpenOption.APPEND)
        val (_, doneOut) = pollCapture {
            cmd.followPoll(log, splice.app.LogFileSource(log), afterTorn, warned())
        }
        assertEquals(listOf("torn-half-done"), doneOut.trim().lines())
    }

    @org.junit.jupiter.api.Test
    fun `the head filter rides the delta path - DR-100`(@TempDir tmp: Path) {
        val log = tmp.resolve("daemon.log")
        Files.writeString(log, "[claudex] seed\n")
        val baseline = Files.size(log)
        Files.writeString(
            log,
            "[claudex] mine\n[claude-grok] theirs\n[claudex] mine too\n",
            java.nio.file.StandardOpenOption.APPEND,
        )
        val (next, out) = pollCapture {
            LogsCommand().followPoll(log, splice.app.LogFileSource(log, "[claudex]"), baseline, warned())
        }
        assertEquals(listOf("[claudex] mine", "[claudex] mine too"), out.trim().lines())
        assertEquals(Files.size(log), next, "filtered-out bytes still advance the baseline")
    }

    @org.junit.jupiter.api.Test
    fun `a shrink re-baselines with the bounded snapshot - roll control`(@TempDir tmp: Path) {
        val log = tmp.resolve("daemon.log")
        Files.writeString(log, (1..30).joinToString("") { "gen1 $it\n" })
        val bigBaseline = Files.size(log)
        Files.writeString(log, "gen2 a\ngen2 b\n") // truncating rewrite = the rotation roll shape
        val (next, out) = pollCapture {
            LogsCommand().followPoll(log, splice.app.LogFileSource(log), bigBaseline, warned())
        }
        assertEquals(listOf("gen2 a", "gen2 b"), out.trim().lines(), "a roll resets to the bounded tail")
        assertEquals(Files.size(log), next)
    }
}
