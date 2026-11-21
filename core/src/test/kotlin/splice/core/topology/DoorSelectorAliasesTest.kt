// gateway-no-heads: `[door.selector_aliases]` names an old selector prefix and the provider it stood for, so a custom
// head prefix keeps working once its head is gone. A prefix that reads no selector, or a provider splice.toml does not
// declare, is a finding at its own path.
package splice.core.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DoorSelectorAliasesTest {
    private val ollama = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "http://127.0.0.1:11434/v1",
        auth = AuthConfig("api-key"),
    )

    private fun findings(aliases: Map<String, String>): List<TopologyFinding> {
        val providers = mapOf("ollama" to ollama, "other" to ollama)
        return DoorChecks(Topology(providers = providers, door = DoorConfig(selectorAliases = aliases))).findings()
    }

    @Test
    fun `an alias of a prefix to a provider splice toml declares says nothing`() {
        assertEquals(emptyList<TopologyFinding>(), findings(mapOf("claude-local--" to "ollama")))
    }

    @Test
    fun `an old custom prefix is an alias too, and so is the one a head gave`() {
        assertEquals(emptyList<TopologyFinding>(), findings(mapOf("zeta--" to "ollama", "qz--" to "other")))
    }

    @Test
    fun `a prefix with no end, a provider not declared, and another provider's own prefix are each a finding`() {
        val said = findings(
            mapOf("local" to "ollama", "--" to "ollama", "claude-gone--" to "nobody", "claude-other--" to "ollama"),
        )
        val paths = listOf("local", "--", "claude-gone--", "claude-other--").map { "door.selector_aliases.\"$it\"" }
        assertEquals(paths.sorted(), said.map { it.path }.sorted())
    }

    @Test
    fun `a provider's own prefix may be aliased to itself`() {
        assertEquals(emptyList<TopologyFinding>(), findings(mapOf("claude-ollama--" to "ollama")))
    }
}
