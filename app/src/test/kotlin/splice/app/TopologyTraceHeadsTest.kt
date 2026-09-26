// NEW: LAYOUT-01 — `splice trace` moved to features/turns and takes its heads through a port; what
// app hands it is the configured topology's head names, or the path it could not read.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import splice.head.trace.TraceHeads
import splice.topology.TopologyLoader
import java.nio.file.Path

class TopologyTraceHeadsTest {

    private fun env(config: Path) = EnvReader { name -> if (name == "SPLICE_CONFIG") config.toString() else null }

    @Test
    fun `the heads are the configured topology's, named with the file they came from`(@TempDir tmp: Path) {
        val config = tmp.resolve("splice.toml")
        val topology = TopologyLoader.loadOrMaterialize(config)

        val heads = TopologyTraceHeads().load(env(config))

        assertEquals(TraceHeads.Configured(config.toString(), topology.heads.keys), heads)
        assertTrue("openrouter" in (heads as TraceHeads.Configured).names, heads.toString())
    }

    @Test
    fun `a topology that cannot be read is reported with its path, not as a topology with no heads`(
        @TempDir tmp: Path,
    ) {
        val missing = tmp.resolve("absent.toml")

        val heads = TopologyTraceHeads().load(env(missing))

        assertTrue(heads is TraceHeads.Unreadable && heads.path == missing.toString(), heads.toString())
    }
}
