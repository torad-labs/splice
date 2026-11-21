// The rows a `claude` on the door lists in /model (docs/specs/door-default.md, decision 4): a signed-in account's
// models, and the models a person added on an API key of their own, such as a custom API at work; of a key
// provider's own endpoint list, only what splice.toml declares, unless it declares nothing.
package splice.app.desktop

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.ProviderLogin
import splice.app.provider.ServedProvider
import splice.client.models.AnthropicTiers
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.usage.QuotaFull
import splice.head.GatewayTurn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Path

internal class DoorPickerRowsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `two providers each list the models they declare`() {
        val large = ModelEntry("team-large", "Team Large", contextWindow = 200_000)
        val small = ModelEntry("team-small", "Team Small", contextWindow = 200_000)
        val lineup = DesktopLineup(
            listOf(provider("work-a", rows = listOf(large)), provider("work-b", rows = listOf(small))),
            listOf(model("work-a", "team-large"), model("work-b", "team-small")),
            emptyList(),
            SelectorAliases(listOf("work-a", "work-b")),
        )
        assertEquals(
            listOf("work-a--team-large", "work-b--team-small"),
            DoorCatalog(catalogued(lineup)).picker().map { it.model },
        )
    }

    @Test
    fun `a model added on a custom API's key is in the picker beside the signed-in accounts' models`() {
        val lineup = lineupOf(
            provider("codex", login = true) to listOf(
                model("codex", "gpt-6.1-sol"),
                model("codex", "gpt-6-luna", true),
            ),
            provider("work") to listOf(model("work", "acme-coder")),
        )
        assertEquals(
            listOf("codex--gpt-6.1-sol", "codex--gpt-6-luna", "work--acme-coder"),
            lineup.picker.map { it.model },
        )
    }

    @Test
    fun `a key provider lists the models splice toml declares for it, not the rest of its endpoint's list`() {
        val lineup = lineupOf(
            provider("openrouter") to listOf(
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
    fun `a key provider that declares no model lists every model its endpoint lists`() {
        val lineup = lineupOf(
            provider("gateway") to listOf(model("gateway", "team-large", true), model("gateway", "team-small", true)),
        )
        assertEquals(listOf("gateway--team-large", "gateway--team-small"), lineup.picker.map { it.model })
    }

    @Test
    fun `the Claude model picked after a provider switch runs as named with the selected effort`() {
        val lineup = lineupOf(
            provider("work") to listOf(model("work", "team-large")),
            provider("claude", rows = listOf(ModelEntry("claude-opus-5-5", "Opus 5.5", contextWindow = 200_000))) to
                listOf(model("claude", "claude-opus-5-5")),
        )
        val routing = DoorRouting()
        val first = ask("work--team-large")
        assertEquals("work", routing.of(lineup, first, null, named = true)?.provider?.key)
        val discovered = DoorCatalog(catalogued(lineup)).discovery().getValue("data").jsonArray
        val opus = discovered.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            .single { it == "claude-opus-5-5" }
        val selected = ask(opus)
        val routed = checkNotNull(routing.of(lineup, selected, null, named = true))
        assertEquals("claude", routed.provider.key)
        assertEquals("claude-opus-5-5", routed.upstream)
        assertEquals(
            """{"model":"claude-opus-5-5","output_config":{"effort":"high"}}""",
            selected.asking(routed.upstream).decodeToString(),
        )
        assertNull(routing.of(lineup, ask("claude-opus-99"), null, named = true))
    }

    @Test
    fun `discovery advertises the serving ceiling rather than the compaction target`() {
        val id = "claude-opus-5-5"
        val row = ModelEntry(id, "Opus 5.5", contextWindow = 200_000, maxContextWindow = 1_000_000)
        val lineup = lineupOf(provider("claude", rows = listOf(row)) to listOf(model("claude", id)))
        val discovered = DoorCatalog(catalogued(lineup)).discovery().getValue("data").jsonArray.single().jsonObject
        assertEquals("1000000", discovered.getValue("max_input_tokens").jsonPrimitive.content)
        assertEquals("true", discovered.getValue("supports_1m").jsonPrimitive.content)
    }

    @Test
    fun `Desktop discovery lists its working selector and routes it to the same provider model`() {
        val id = "gpt-6.2-sol"
        val row = ModelEntry(id, "GPT-6.2 Sol", contextWindow = 272_000)
        val lineup = catalogued(
            lineupOf(provider("claude-codex", rows = listOf(row)) to listOf(model("claude-codex", id))),
        )
        val catalog = DoorCatalog(lineup)
        val gateway = catalog.discovery().getValue("data").jsonArray.single().jsonObject
        val desktop = catalog.discovery(surface = ModelDiscovery.DESKTOP).getValue("data").jsonArray.single().jsonObject
        val selector = desktop.getValue("id").jsonPrimitive.content

        assertEquals("claude-codex--gpt-6.2-sol", gateway.getValue("id").jsonPrimitive.content)
        assertTrue(splice.core.model.ClientModelNames.kept(selector))
        assertEquals(gateway - "id", desktop - "id")
        assertEquals(id, DoorRouting().of(lineup, ask(selector), null, named = true)?.upstream)
    }

    @Test
    fun `Muse metadata reaches both discovery paths without changing its selector`() {
        val id = "muse-spark-1.3-contributor"
        val row = ModelEntry(id, contextWindow = 1_000_000)
        val lineup = catalogued(
            lineupOf(
                provider("muse", rows = listOf(row), baseUrl = "https://api.meta.ai/v1") to
                    listOf(model("muse", id)),
            ),
        )
        val catalog = DoorCatalog(lineup)
        val gateway = catalog.discovery().getValue("data").jsonArray.single().jsonObject
        val desktop = catalog.discovery(surface = ModelDiscovery.DESKTOP).getValue("data").jsonArray.single().jsonObject

        assertEquals("Muse Spark 1.3 Contributor", desktop.getValue("display_name").jsonPrimitive.content)
        assertTrue("train Meta" in desktop.getValue("description").jsonPrimitive.content)
        val thinking = desktop.getValue("thinking").jsonObject
        assertEquals("effort", thinking.getValue("type").jsonPrimitive.content)
        val options = thinking.getValue("effort_options").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("minimal", "low", "medium", "high", "xhigh"),
            options.map { it.getValue("id").jsonPrimitive.content },
        )
        assertEquals(
            "true",
            options.single { it.getValue("id").jsonPrimitive.content == "high" }
                .getValue("recommended").jsonPrimitive.content,
        )
        assertEquals(gateway - "id", desktop - "id")
        val selector = desktop.getValue("id").jsonPrimitive.content
        assertEquals(id, DoorRouting().of(lineup, ask(selector), null, named = true)?.upstream)
    }

    @Test
    fun `reasoning without named effort levels does not invent an effort selector`() {
        val fact = CatalogModel(provider = "work", id = "model", label = "Model", reasons = true)
        val model = ServedModel(fact, provider("work"), listOf(model("work", "model")), claude = null)

        val discovered = DoorModelWire(AnthropicTiers(), emptyMap()).discovered(model, "work--model")

        assertEquals("true", discovered.getValue("supports_reasoning").jsonPrimitive.content)
        assertNull(discovered["thinking"])
        assertNull(discovered["capabilities"])
    }

    private fun ask(model: String): DoorAsk = DoorAsk(
        buildJsonObject {
            put("model", model)
            put("output_config", buildJsonObject { put("effort", "high") })
        },
        model,
        session = "same-session",
    )

    private fun lineupOf(vararg providers: Pair<ServedProvider, List<DoorModel>>): DesktopLineup =
        DesktopLineup(
            providers.map { it.first },
            providers.flatMap { it.second },
            emptyList(),
            SelectorAliases(providers.map { it.first.key }),
        )

    /** [lineup] with the catalog its providers publish, as the daemon publishes it. */
    private fun catalogued(lineup: DesktopLineup): DesktopLineup {
        val published = PublishedCatalog(CatalogBuild(), CatalogFile(tmp.resolve("model-catalog.json"), {}), Unconfined)
        runBlocking { published.publish(lineup) }
        return lineup.copy(catalog = published.current)
    }

    private fun provider(
        key: String,
        login: Boolean = false,
        rows: List<ModelEntry> = listOf(ModelEntry("m", "M", contextWindow = 200_000)),
        baseUrl: String = "",
    ): ServedProvider = FakeProvider(
        key,
        ModelCatalog(
            discoveryPrefix = "$key--",
            models = rows,
            defaultContextWindow = 200_000,
            vendorBaseUrl = baseUrl,
        ),
        login,
    )

    private fun model(provider: String, id: String, discovered: Boolean = false): DoorModel =
        DoorModel("$provider--$id", id, provider, id, discovered = discovered)
}

/** A provider the door reads, with nothing behind it: it is read for its catalog and never asked to serve a turn. */
private class FakeProvider(
    override val key: String,
    override val catalog: ModelCatalog,
    override val usesLogin: Boolean,
) :
    ServedProvider {
    override val tiers: Map<String, String> = emptyMap()
    override val pinnedModel: String = "m"
    override val dialect: String? = null
    override val usage: HeadUsageSource = HeadUsageSource { UsageView(0, 0, null) }
    override val forwardsClientAuth: Boolean = false
    override val maxRequestBytes: Int = 0
    override val requestReadTimeoutMs: Long = 0

    override fun quotaFull(): QuotaFull? = null

    override suspend fun login(): ProviderLogin = ProviderLogin.Ready

    override suspend fun serve(call: ApplicationCall, turn: GatewayTurn) = error("a picker test serves no turn")

    override suspend fun countTokens(call: ApplicationCall, turn: GatewayTurn) = error("a picker test counts no turn")
}
