// gateway-no-heads: the names a team's grants call a provider by each mean one provider. A head's command or key keeps
// meaning the head's provider, and a provider key that a head's name also says is reachable by no grant, so a person
// allowed `other` is never served on a second provider.
package splice.app.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.topology.TopologyLoader

class TeamNamesTest {
    private fun provider(key: String) = """
        [providers.$key]
        dialect = "openai-chat"
        base_url = "http://127.0.0.1:9/v1"
        auth = { kind = "api-key", env = "K" }
        [[providers.$key.models]]
        id = "m"
        context_window = 1000
    """.trimIndent() + "\n"

    @Test
    fun `a provider is called by its key and by the command and key of a head that runs it`() {
        val names = TeamNames(
            TopologyLoader.parse(
                provider("xai") + """
                    [heads.grok]
                    provider = "xai"
                    port = 3191
                    discovery_prefix = "claude-grok--"
                    pinned_model = "m"
                    claude = { command = "claude-grok" }
                """.trimIndent() + "\n",
            ),
        )
        assertEquals(setOf("xai", "grok", "claude-grok"), names.of("xai"))
    }

    @Test
    fun `a name that is a head's command and another provider's key means the head's provider alone`() {
        val names = TeamNames(
            TopologyLoader.parse(
                provider("alpha") + provider("other") + """
                    [heads.shadow]
                    provider = "alpha"
                    port = 3191
                    discovery_prefix = "claude-shadow--"
                    pinned_model = "m"
                    claude = { command = "other" }
                """.trimIndent() + "\n",
            ),
        )
        assertEquals(setOf("alpha", "shadow", "other"), names.of("alpha"))
        assertEquals(emptySet<String>(), names.of("other"), "the other provider stays denied to a grant of that name")
    }
}
