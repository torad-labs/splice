// NEW: Oct 10, 2026 — a fresh install's history window is written down, in the file the person can
// read. 35 days is a default only here: every install that predates the setting keeps the window it
// already had (HistoryWindowTest), because an upgrade never shortens history on its own.
//
// WHY IT IS PINNED THROUGH THE WHOLE PATH. The number has to survive three hops to mean anything:
// the starter's bytes, the TOML parse into [defaults], and the knob merge that answers the daemon.
// Asserting only the text would pass with a key no layer reads, which is how an inert knob ships.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.perf.HISTORY_DEFAULT_DAYS
import splice.core.perf.HistoryWindow
import java.nio.file.Files
import java.nio.file.Path

class FreshInstallHistoryWindowTest {
    @Test
    fun `a fresh install keeps the shipped window, named in its own splice-toml`(@TempDir root: Path) {
        val file = root.resolve("config/splice.toml")
        val topology = TopologyLoader.loadOrMaterialize(file)

        assertEquals(
            HISTORY_DEFAULT_DAYS.toString(),
            topology.defaults["historyRetentionDays"],
            "the starter carries the window as a value, not as a number buried in the binary",
        )
        assertTrue(
            Files.readString(file).contains("Settings > Your data"),
            "and says where the person changes it without editing a file",
        )

        val config = ConfigService(
            statePaths = StatePaths(baseOverride = root.resolve("state")),
            headOverrides = topology.defaults,
            envReader = { null },
        ).getConfig()
        assertEquals(
            HistoryWindow(HISTORY_DEFAULT_DAYS),
            config.historyWindow,
            "the written window is the one the daemon trims against",
        )
    }
}
