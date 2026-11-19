// The rows a `claude` on the door lists in /model (docs/specs/door-default.md, decision 4): a signed-in account's
// models, and the models a person added on an API key of their own, such as a custom API at work; of a key head's own
// endpoint list, only what splice.toml declares, unless it declares nothing.
package splice.app.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.launch.LaunchSpec
import splice.share.SharedHead

internal class DoorPickerRowsTest {
    @Test
    fun `a model added on a custom API's key is in the picker beside the signed-in accounts' models`() {
        val lineup = lineupOf(
            head("codex", login = true) to listOf(model("codex", "gpt-6.1-sol"), model("codex", "gpt-6-luna", true)),
            head("work") to listOf(model("work", "acme-coder")),
        )
        assertEquals(
            listOf("codex--gpt-6.1-sol", "codex--gpt-6-luna", "work--acme-coder"),
            lineup.picker.map { it.model },
        )
    }

    @Test
    fun `a key head lists the models splice toml declares for it, not the rest of its endpoint's list`() {
        val lineup = lineupOf(
            head("openrouter") to listOf(
                model("openrouter", "anthropic/claude-haiku-4.5"),
                model("openrouter", "qwen/qwen4-max", true),
                model("openrouter", "google/gemini-4-neon", true),
            ),
        )
        assertEquals(listOf("openrouter--anthropic/claude-haiku-4.5"), lineup.picker.map { it.model })
        // The rest still runs when named.
        assertEquals("qwen/qwen4-max", lineup.find("openrouter--qwen/qwen4-max")?.second?.upstream)
    }

    @Test
    fun `a key head that declares no model lists every model its endpoint lists`() {
        val lineup = lineupOf(
            head("gateway") to listOf(model("gateway", "team-large", true), model("gateway", "team-small", true)),
        )
        assertEquals(listOf("gateway--team-large", "gateway--team-small"), lineup.picker.map { it.model })
    }

    private fun lineupOf(vararg heads: Pair<DoorHead, List<DoorModel>>): DesktopLineup =
        DesktopLineup(heads.map { it.first }, heads.flatMap { it.second }, emptyList())

    private fun head(key: String, login: Boolean = false): DoorHead {
        val catalog = ModelCatalog(
            discoveryPrefix = "$key--",
            models = listOf(ModelEntry("m", "M", contextWindow = 200_000)),
            defaultContextWindow = 200_000,
        )
        return DoorHead(key, Relay(key), catalog, emptyMap(), "m", null, login)
    }

    private fun model(head: String, id: String, discovered: Boolean = false): DoorModel =
        DoorModel("$head--$id", id, head, "claude-$head", id, discovered = discovered)

    private class Relay(override val key: String) : SharedHead {
        override val command: String = "claude-$key"
        override val port: Int = 0

        override fun running(): Boolean = true

        override fun launch(): LaunchSpec? = null

        override fun windows(): Map<String, Long> = emptyMap()

        override fun logins(): List<String> = emptyList()

        override fun maxRequestBytes(): Int = 0

        override fun requestReadTimeoutMs(): Long = 0
    }
}
