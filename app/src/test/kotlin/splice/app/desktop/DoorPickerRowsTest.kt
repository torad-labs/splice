// The rows a `claude` on the door lists in /model (docs/specs/door-default.md, decision 4): a signed-in account's
// models, and the models a person added on an API key of their own, such as a custom API at work; of a key head's own
// endpoint list, only what splice.toml declares, unless it declares nothing.
package splice.app.desktop

import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.launch.LaunchSpec
import splice.share.SharedHead
import java.nio.file.Path

internal class DoorPickerRowsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `two commands on one provider each list the models they declare`() {
        val large = ModelEntry("team-large", "Team Large", contextWindow = 200_000)
        val small = ModelEntry("team-small", "Team Small", contextWindow = 200_000)
        val lineup = DesktopLineup(
            listOf(head("work-a", rows = listOf(large)), head("work-b", rows = listOf(small))),
            listOf(model("work-a", "team-large"), model("work-b", "team-small")),
            emptyList(),
            mapOf("work-a" to "work", "work-b" to "work"),
        )
        assertEquals(
            listOf("work-a--team-large", "work-b--team-small"),
            DoorCatalog(catalogued(lineup)).picker().map { it.model },
        )
    }

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

    @Test
    fun `discovery advertises the serving ceiling rather than the compaction target`() {
        val id = "claude-opus-5-5"
        val row = ModelEntry(id, "Opus 5.5", contextWindow = 200_000, maxContextWindow = 1_000_000)
        val lineup = lineupOf(head("claude", rows = listOf(row)) to listOf(model("claude", id)))
        val discovered = DoorCatalog(catalogued(lineup)).discovery().getValue("data").jsonArray.single().jsonObject
        assertEquals("1000000", discovered.getValue("max_input_tokens").jsonPrimitive.content)
        assertEquals("true", discovered.getValue("supports_1m").jsonPrimitive.content)
    }

    private fun lineupOf(vararg heads: Pair<DoorHead, List<DoorModel>>): DesktopLineup =
        DesktopLineup(
            heads.map { it.first },
            heads.flatMap { it.second },
            emptyList(),
            heads.associate { it.first.key to it.first.key },
        )

    /** [lineup] with the catalog its heads' providers publish, as the daemon publishes it. */
    private fun catalogued(lineup: DesktopLineup): DesktopLineup {
        val published = PublishedCatalog(CatalogBuild(), CatalogFile(tmp.resolve("model-catalog.json"), {}), Unconfined)
        runBlocking { published.publish(lineup) }
        return lineup.copy(catalog = published.current)
    }

    private fun head(
        key: String,
        login: Boolean = false,
        rows: List<ModelEntry> = listOf(ModelEntry("m", "M", contextWindow = 200_000)),
    ): DoorHead {
        val catalog = ModelCatalog(
            discoveryPrefix = "$key--",
            models = rows,
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

        override fun pin(login: String, session: String): Boolean = false

        override fun maxRequestBytes(): Int = 0

        override fun requestReadTimeoutMs(): Long = 0
    }
}
