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

    private fun findings(aliases: Map<String, String>) =
        DoorChecks(Topology(providers = mapOf("ollama" to ollama), door = DoorConfig(selectorAliases = aliases)))
            .findings()

    @Test
    fun `an alias of a prefix to a provider splice toml declares says nothing`() {
        assertEquals(emptyList<TopologyFinding>(), findings(mapOf("claude-local--" to "ollama")))
    }

    @Test
    fun `a prefix that reads no selector, and a provider that is not declared, are each a finding at their own path`() {
        val said = findings(mapOf("local" to "ollama", "claude-gone--" to "nobody", "claude---" to "ollama"))
        val paths = listOf("local", "claude-gone--", "claude---").map { "door.selector_aliases.\"$it\"" }
        assertEquals(paths, said.map { it.path })
    }
}
