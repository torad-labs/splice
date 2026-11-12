// the Claude head's one login place is Claude Code's own home, under a synthetic home.
package splice.app.auth.claude

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.core.config.StatePaths
import splice.topology.TopologyLoader
import java.nio.file.Path

class ClaudeLoginLocationsTest {
    @TempDir
    lateinit var home: Path

    private fun locations(config: String? = null): List<ClaudeLoginLocation> {
        val extra = config?.let { "\n[heads.claude-splice.claude]\nconfig_dir = \"$it\"" }.orEmpty()
        val topology = TopologyLoader.parse(
            """
            [providers.native]
            dialect = "anthropic-passthrough"
            base_url = "https://api.anthropic.com"
            [providers.native.auth]
            kind = "client"
            [heads.claude-splice]
            provider = "native"
            port = 3100
            discovery_prefix = "native--"
            """.trimIndent() + extra,
        )
        return ClaudeLoginLocations(home, StatePaths(baseOverride = home.resolve("state"))).read(topology)
    }

    @Test
    fun `the Claude head has one login place, Claude Code's own home`() {
        val places = locations()
        assertEquals(listOf(ClaudeLoginPlaceId.NATIVE), places.map { it.id })
        assertEquals(home.resolve(".claude/.credentials.json"), places.single().credentials)
        assertEquals(home.resolve(".claude.json"), places.single().target.accountFile)
        assertEquals("claude-splice", places.single().target.head.key)
    }

    @Test
    fun `a declared config directory on the head makes no second login place`() {
        for (declared in listOf("~/custom", "/absolute/custom", "relative", "~")) {
            val places = locations(declared)
            assertEquals(1, places.size, declared)
            assertEquals(home.resolve(".claude"), places.single().target.head.configDir)
        }
    }
}
