// NEW: V4-109 — TopologyStatePaths is the one answer to "where does the daemon keep its state", so
// its precedence is the daemon's: a usable [daemon].state_dir over the environment, the environment
// over the default layout, and a read of splice.toml that never writes one.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.DaemonConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class TopologyStatePathsTest {

    private fun env(vararg pairs: Pair<String, String>) = EnvReader { name -> pairs.toMap()[name] }

    @Test
    fun `a declared state_dir wins over the environment, as the daemon resolves it`(@TempDir tmp: Path) {
        val resolver = TopologyStatePaths(env("SPLICE_STATE_DIR" to "$tmp/env"), homeDir = tmp)

        val paths = resolver.of(Topology(daemon = DaemonConfig(stateDir = "$tmp/declared")))

        assertEquals(tmp.resolve("declared"), paths.stateDir)
    }

    @Test
    fun `an absent or blank state_dir leaves the environment in charge`(@TempDir tmp: Path) {
        val resolver = TopologyStatePaths(env("SPLICE_STATE_DIR" to "$tmp/env"), homeDir = tmp)

        assertEquals(tmp.resolve("env"), resolver.of(null).stateDir)
        assertEquals(tmp.resolve("env"), resolver.of(Topology()).stateDir)
        assertEquals(tmp.resolve("env"), resolver.of(Topology(daemon = DaemonConfig(stateDir = " "))).stateDir)
    }

    @Test
    fun `current reads splice toml and never materializes a starter`(@TempDir tmp: Path) {
        val config = tmp.resolve("splice.toml")
        val resolver = TopologyStatePaths(
            env("SPLICE_CONFIG" to config.toString(), "SPLICE_STATE_DIR" to "$tmp/env"),
            homeDir = tmp,
        )

        assertEquals(tmp.resolve("env"), resolver.current().stateDir)
        assertFalse(Files.exists(config), "asking where state lives must not write splice.toml")

        Files.writeString(config, "[daemon]\nstate_dir = \"$tmp/declared\"\n")
        assertEquals(tmp.resolve("declared"), resolver.current().stateDir)

        Files.writeString(config, "[daemon\n")
        assertEquals(tmp.resolve("env"), resolver.current().stateDir, "an unparseable file declares nothing")
    }
}
