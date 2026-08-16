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
