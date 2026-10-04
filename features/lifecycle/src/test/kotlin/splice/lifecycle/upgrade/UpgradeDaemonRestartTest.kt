// NEW: V4-220 item 4 — `splice upgrade`'s restart lets a compaction in flight finish first. The release
// activates AFTER the idle wait, and a compaction that starts meanwhile is the one turn a restart must
// not cut; the console runs this very restart (its upgrade is `splice upgrade` out of process), so this
// is where "an upgrade from the console never costs a compaction" is held.
package splice.lifecycle.upgrade

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import java.nio.file.Path

private const val VERSION = "9.9.9"

class UpgradeDaemonRestartTest {

    private val lines = mutableListOf<String>()
    private val compaction = InflightRead.Count(1, listOf(CompactionSlot("claudex", 1_000)))
    private var reads = ArrayDeque<InflightRead>()
    private var unreadAtRestart = -1
    private var verbRestarts = 0

    /** A unit that runs [unitJar] and is active when [active]; its restart records what was still unread. */
    private fun process(unitJar: Path, active: Boolean) = UpgradeProcess { cmd, _ ->
        when (cmd.getOrNull(2)) {
            "show" -> UpgradeExit(0, "java -jar $unitJar daemon")
            "is-active" -> UpgradeExit(0, if (active) "active\n" else "inactive\n")
            "restart" -> UpgradeExit(0, "").also { unreadAtRestart = reads.size }
            else -> UpgradeExit(0, "")
        }
    }

    private fun daemon(jar: Path, active: Boolean = true) = UpgradeDaemon(
        TerminalOutput { lines += it },
        process(jar, active),
        { reads.removeFirstOrNull() ?: InflightRead.Count(0) },
        restartVerb = {
            verbRestarts++
            unreadAtRestart = reads.size
            true
        },
        healthVersion = { VERSION },
        pollMs = 1,
        confirmPollMs = 1,
        userUnit = "splice.service",
    )

    /** RED before V4-220 (as UpgradeCommandTest's arm, 2026-09-25): the unit restarted with both compaction
     *  reads still unmade, "expected: <0> but was: <2>". */
    @Test
    fun `a compaction in flight is waited for before the unit restarts`(@TempDir home: Path) {
        val jar = home.resolve("splice.jar")
        reads = ArrayDeque(listOf(compaction, compaction))
        assertEquals(DaemonRestarted.Serving, daemon(jar).restart(jar, VERSION, now = false))
        assertEquals(0, unreadAtRestart, "the unit restarted while a compaction was in flight: $lines")
        assertTrue(lines.any { it.contains("waiting for 1 compaction (claudex") }, "$lines")
    }

    @Test
    fun `the restart verb's path waits too`(@TempDir home: Path) {
        val jar = home.resolve("splice.jar")
        reads = ArrayDeque(listOf(compaction))
        daemon(jar, active = false).restart(jar, VERSION, now = false)
        assertEquals(1 to 0, verbRestarts to unreadAtRestart)
    }

    /** `--now` skips every wait, the compaction's included, as `splice restart --now` does. */
    @Test
    fun `--now restarts without waiting for a compaction`(@TempDir home: Path) {
        val jar = home.resolve("splice.jar")
        reads = ArrayDeque(listOf(compaction, compaction))
        daemon(jar).restart(jar, VERSION, now = true)
        assertEquals(2, unreadAtRestart, "--now waited: $lines")
    }
}
