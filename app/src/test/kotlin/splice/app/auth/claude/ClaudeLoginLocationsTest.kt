// command-local credentials, account files and safe-copy stores are independent under a synthetic home.
package splice.app.auth.claude

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.app.head.HeadConfigDirs
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
    fun `default Claude account is home-level while separate command account is directory-local`() {
        val places = locations()
        assertEquals(ClaudeLoginPlaceId.NATIVE, places[0].id)
        assertEquals(home.resolve(".claude/.credentials.json"), places[0].credentials)
        assertEquals(home.resolve(".claude.json"), places[0].target.accountFile)
        assertEquals(home.resolve(".claude-claude-splice/.credentials.json"), places[1].credentials)
        assertEquals(home.resolve(".claude-claude-splice/.claude.json"), places[1].target.accountFile)
        assertTrue(places[0].storeDir != places[1].storeDir)
        assertEquals("claude-splice", places[0].target.head.key)
        assertEquals("claude-splice", places[1].target.head.key)
    }

    @Test
    fun `declared separate directory uses the launch resolvers exact home expansion`() {
        for (declared in listOf("~/custom", "/absolute/custom", "relative", "~")) {
            val separate = locations(declared)[1]
            assertEquals(HeadConfigDirs.of("claude-splice", declared, home), separate.target.head.configDir)
            assertEquals(separate.target.head.configDir.resolve(".claude.json"), separate.target.accountFile)
        }
        assertEquals(home.resolve("custom"), locations("~/custom")[1].target.head.configDir)
    }
}
