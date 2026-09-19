// NEW: V4-110 — the doctor row for a [daemon].state_dir that cannot be resolved to a path. The
// boot fallback (Main.kt statePathsFor) drops such a value silently BY DESIGN; this row makes the
// drop visible, naming the value and the default used, and leaves the operator a next action.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.CheckStatus
import splice.app.cli.DoctorCheck
import splice.app.cli.DoctorConfigChecks
import splice.app.cli.DoctorTopology
import splice.core.topology.DaemonConfig
import splice.core.topology.Topology
import java.nio.file.Paths

class DoctorStateDirCheckTest {

    @Test
    fun `an unusable state_dir warns and names both the value and the default`() {
        // A NUL byte is the one character java.nio.file.Paths.get refuses; Main.kt falls back on it.
        val declared = "\u0000"
        val row = stateDirRows(Topology(daemon = DaemonConfig(stateDir = declared))).single()
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("state_dir"), row.detail)
        assertTrue(row.detail.contains(declared), "the row must name the unusable value: ${row.detail}")
        assertTrue(row.detail.contains("default state dir"), "the row must name the default used: ${row.detail}")
        assertTrue(row.fix.orEmpty().isNotBlank(), "the doctor must leave a next action, not a bare statement")
        assertTrue(row.fix.orEmpty().contains("state_dir"), row.fix.orEmpty())
    }

    @Test
    fun `a blank state_dir is silent - treated as absent`() {
        val topology = Topology(daemon = DaemonConfig(stateDir = "   "))
        assertEquals(emptyList<DoctorCheck>(), stateDirRows(topology))
    }

    @Test
    fun `a usable state_dir is silent`() {
        val topology = Topology(daemon = DaemonConfig(stateDir = "/tmp/splice-state"))
        assertEquals(emptyList<DoctorCheck>(), stateDirRows(topology))
    }

    @Test
    fun `no state_dir is silent`() {
        assertEquals(emptyList<DoctorCheck>(), stateDirRows(Topology()))
    }

    private fun stateDirRows(topology: Topology): List<DoctorCheck> =
        DoctorConfigChecks()
            .configurationChecks(DoctorTopology.Parsed(topology), Paths.get("/tmp/splice.toml"))
            .filter { it.detail.contains("state_dir") }
}
