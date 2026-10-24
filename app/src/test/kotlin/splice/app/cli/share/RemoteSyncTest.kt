// NEW: LAN share, round 12 of PR #419's review. The goal: `splice remote sync` says what it did. A machine that does
// not answer has rows that could not be checked, so sync never calls them current or exits as if it had: it says the
// machine did not answer and exits non-zero. RemoteMachines as `splice remote sync` runs it, over a temp splice.toml
// that names a machine at a port nothing listens on, as when that machine sleeps.
package splice.app.cli.share

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.peer.PeerExit
import splice.configuration.add.DaemonRestart
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class RemoteSyncTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a machine that does not answer is said, never called current, and the sync exits non-zero`() {
        val config = tmp.resolve("splice.toml")
        val asleep = TestPorts.reserve()
        Files.writeString(
            config,
            "[remotes.torad-server]\nurl = \"https://127.0.0.1:$asleep\"\nfingerprint = \"sha256:${"a".repeat(64)}\"\n",
        )
        val vars = mapOf(
            "SPLICE_CONFIG" to config.toString(),
            "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
            "HOME" to tmp.toString(),
        )
        val said = mutableListOf<String>()
        val lines = TerminalOutput { said += it }

        val exit = RemoteMachines(lines, lines, EnvReader { vars[it] }, DaemonRestart { true }).sync(null)

        assertNotEquals(PeerExit.DONE, exit, "the sync did not check every row: $said")
        assertFalse(said.any { "every row is current" in it }, "no row is called current: $said")
        assertTrue(said.any { "answers" in it }, "it says the machine did not answer: $said")
    }
}
