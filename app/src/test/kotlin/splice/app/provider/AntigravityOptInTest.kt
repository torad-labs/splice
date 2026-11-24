// desktop-antigravity: splice.toml's `[daemon] antigravity` decides whether the daemon asks Google's agy for its models
// at boot. Absent keeps what every install had before the key (on); false never runs agy and serves no Antigravity.
package splice.app.provider

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.topology.TopologyLoader
import java.nio.file.Path

class AntigravityOptInTest(@param:TempDir private val tmp: Path) {
    @Test
    fun `the key reads false when set so, and absent when splice toml says nothing`() {
        assertEquals(false, TopologyLoader.parse("[daemon]\nantigravity = false\n").daemon.antigravity)
        assertEquals(true, TopologyLoader.parse("[daemon]\nantigravity = true\n").daemon.antigravity)
        assertNull(TopologyLoader.parse("[daemon]\ncontrol_port = 3096\n").daemon.antigravity)
    }

    @Test
    fun `turned off, the daemon never asks agy, serves no Antigravity and says why`() = runBlocking {
        val said = mutableListOf<String>()
        val config = ConfigService(StatePaths(baseOverride = tmp.resolve("state")))
        val join = AntigravityJoin(config, LogSink { said += it }, enabled = false)
        assertFalse(join.join(this, ServingProviders { emptyList() }))
        assertTrue(join().isEmpty())
        assertEquals(1, said.size, said.toString())
        assertTrue(said.single().startsWith("[antigravity] off (Desktop harnesses"), said[0])
    }
}
