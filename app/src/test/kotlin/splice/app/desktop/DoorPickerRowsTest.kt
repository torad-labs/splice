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
import splice.app.desktop.catalog.CatalogBuild
import splice.app.desktop.catalog.CatalogFile
import splice.app.desktop.catalog.CatalogModel
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DesktopModelSettings
import splice.app.desktop.catalog.DoorCatalog
import splice.app.desktop.catalog.DoorModel
import splice.app.desktop.catalog.DoorModelWire
import splice.app.desktop.catalog.ModelDiscovery
import splice.app.desktop.catalog.ProviderCatalog
import splice.app.desktop.catalog.PublishedCatalog
import splice.app.desktop.catalog.SelectorAliases
import splice.app.desktop.catalog.ServedModel
import splice.app.provider.ProviderLogin
import splice.app.provider.ServedProvider
import splice.client.models.AnthropicTiers
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.ChatEffortVocabularyConfig
import splice.core.topology.desktop.DesktopConfig
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
    fun `a provider only Desktop runs is in Desktop's list and in no picker the door serves`() {
        val neon = ModelEntry("gemini-4-neon", "Gemini 4 Neon", contextWindow = 1_048_576)
        val lineup = DesktopLineup(
            listOf(provider("work-a")),
            listOf(model("work-a", "m")),
            emptyList(),
            SelectorAliases(listOf("work-a")),
            desktopOnly = listOf(provider("antigravity", login = true, rows = listOf(neon))),
            desktopModels = listOf(model("antigravity", "gemini-4-neon")),
        )
        val catalog = DoorCatalog(catalogued(lineup))
        assertEquals(listOf("work-a--m"), catalog.picker().map { it.model })
        assertEquals(
            listOf("work-a--m", "antigravity--gem-4-neon"),
            catalog.inferenceModels(null).map { it.jsonObject["name"]?.jsonPrimitive?.content },
        )
        assertNull(lineup.find("antigravity--gemini-4-neon"))
        val ids = { surface: ModelDiscovery ->
            catalog.discovery(surface = surface).getValue("data").jsonArray
                .map { it.jsonObject["id"]?.jsonPrimitive?.content }
        }
        assertEquals(listOf("work-a--m", "antigravity--gem-4-neon"), ids(ModelDiscovery.DESKTOP))
        assertEquals(listOf("work-a--m"), ids(ModelDiscovery.GATEWAY))
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
    fun `model_slots serve every bare Claude tier id as the provider's own model, held or by default`() {
        val slots = mapOf("opus" to "m-big", "sonnet" to "m-mid", "haiku" to "m-small")
        val lineup = lineupOf(
            mapping("realinfer", slots) to listOf(model("realinfer", "m-big"), model("realinfer", "m-mid")),
        )
        val asked = mapOf("claude-opus-5-5" to "m-big", "claude-sonnet-5-5" to "m-mid", "claude-haiku-4-5" to "m-small")
        // Held: the session named the provider's selector first.
        val held = DoorRouting()
        assertEquals("realinfer", held.of(lineup, ask("realinfer--m-big"), null, named = true)?.provider?.key)
        for ((claude, upstream) in asked) {
            val routed = held.of(lineup, ask(claude), null, named = true)
            assertEquals("realinfer" to upstream, routed?.let { it.provider.key to it.upstream }, claude)
        }
        // First turn, no provider held: the caller's default selector's provider maps the tier.
        for ((claude, upstream) in asked) {
            val routed = DoorRouting().of(lineup, ask(claude), "realinfer--m-mid", named = true)
            assertEquals("realinfer" to upstream, routed?.let { it.provider.key to it.upstream }, claude)
        }
    }

    @Test
    fun `a bare Claude id whose tier the provider does not map is still refused, held or by default`() {
        val lineup = lineupOf(
            mapping("realinfer", mapOf("haiku" to "m-small")) to listOf(model("realinfer", "m-big")),
        )
        val routing = DoorRouting()
        assertEquals("realinfer", routing.of(lineup, ask("realinfer--m-big"), null, named = true)?.provider?.key)
        assertNull(routing.of(lineup, ask("claude-opus-5-5"), null, named = true))
        assertNull(DoorRouting().of(lineup, ask("claude-sonnet-5-5"), "realinfer--m-big", named = true))
        // The small tier still runs the model the provider names for it.
        assertEquals("m-small", routing.of(lineup, ask("claude-haiku-4-5"), null, named = true)?.upstream)
    }

    @Test
    fun `a provider that serves the exact Claude model wins over another's opus slot`() {
        val opus = ModelEntry("claude-opus-5-5", "Opus 5.5", contextWindow = 200_000)
        val lineup = lineupOf(
            mapping("realinfer", mapOf("opus" to "m-big")) to listOf(model("realinfer", "m-big")),
            provider("claude", rows = listOf(opus)) to listOf(model("claude", "claude-opus-5-5")),
        )
        val routing = DoorRouting()
        assertEquals("realinfer", routing.of(lineup, ask("realinfer--m-big"), null, named = true)?.provider?.key)
        val routed = routing.of(lineup, ask("claude-opus-5-5"), null, named = true)
        assertEquals("claude" to "claude-opus-5-5", routed?.let { it.provider.key to it.upstream })
    }

    @Test
    fun `a model the picker spells with the 1M hint runs when a turn names it without the hint`() {
        // Claude Code sends `muse--muse-spark-1.3` for the row it lists as `muse--muse-spark-1.3[1m]`.
        val lineup = lineupOf(
            provider("muse", login = true) to
                listOf(model("muse", "muse-spark-1.3[1m]"), model("muse", "muse-ember-1.0")),
            provider("grok", login = true) to listOf(model("grok", "grok-4.3"), model("grok", "grok-4.3[1m]")),
        )
        assertEquals("muse-spark-1.3[1m]", lineup.find("muse--muse-spark-1.3")?.second?.upstream)
        assertEquals("muse-spark-1.3[1m]", lineup.find("muse--muse-spark-1.3[1m]")?.second?.upstream)
        // A row spelled exactly as asked is the one that runs.
        assertEquals("grok-4.3", lineup.find("grok--grok-4.3")?.second?.upstream)
        assertNull(lineup.find("muse--muse-spark-9"))
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
    fun `a configured 1M window is the bar for a model's 1M variant in Desktop's rows`() {
        val row = ModelEntry("team-wide", "Team Wide", contextWindow = 600_000)
        val lineup = catalogued(lineupOf(provider("work", rows = listOf(row)) to listOf(model("work", "team-wide"))))

        // Desktop's shipped million: a 600,000 window has no 1M variant to advertise.
        val shipped = DoorCatalog(lineup)
        val plain = shipped.discovery(surface = ModelDiscovery.DESKTOP).getValue("data").jsonArray.single().jsonObject
        assertEquals("false", plain.getValue("supports_1m").jsonPrimitive.content)
        assertNull(shipped.inferenceModels(null).single().jsonObject["supports1m"])

        // This install's big window at 500,000: the same model fills it, so both of Desktop's lists say so,
        // while the advertised ceiling stays the provider's own 600,000, never a configured window.
        val configured = DoorCatalog(lineup, DesktopConfig(codeWindow1m = 500_000))
        val wide = configured.discovery(surface = ModelDiscovery.DESKTOP)
            .getValue("data").jsonArray.single().jsonObject
        assertEquals("true", wide.getValue("supports_1m").jsonPrimitive.content)
        assertEquals("600000", wide.getValue("max_input_tokens").jsonPrimitive.content)
        val settings = configured.inferenceModels(null).single().jsonObject
        assertEquals("true", settings.getValue("supports1m").jsonPrimitive.content)
    }

    @Test
    fun `Desktop does not advertise a 1M-only row below its configured big window`() {
        val source = buildJsonObject {
            put("supports_1m", true)
            put("prefer_1m", true)
        }
        val fact = CatalogModel("work", "wide", "Wide", contextWindow = 1_000_000, source = source)
        val lineup = lineupOf(provider("work", login = true) to listOf(model("work", "wide[1m]")))
            .copy(catalog = ProviderCatalog(listOf(fact)))
        val catalog = DoorCatalog(lineup, DesktopConfig(codeWindow1m = 1_500_000))
        val discovery = catalog.discovery(surface = ModelDiscovery.DESKTOP)
            .getValue("data").jsonArray.single().jsonObject
        val settings = catalog.inferenceModels(null).single().jsonObject

        assertEquals("false", discovery.getValue("supports_1m").jsonPrimitive.content)
        assertTrue(discovery["prefer_1m"]?.jsonPrimitive?.content != "true")
        assertNull(settings["supports1m"])
        assertNull(settings["prefer1m"])
        assertEquals("work--wide[1m]", catalog.picker().single().model)
        val terminal = catalog.discovery().getValue("data").jsonArray.single().jsonObject
        assertEquals("true", terminal.getValue("supports_1m").jsonPrimitive.content)
        assertEquals("true", terminal.getValue("prefer_1m").jsonPrimitive.content)
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
    fun `Desktop settings and discovery use the same models while reasoning reaches the picker`() {
        val opus = "claude-opus-5-5"
        val muse = "muse-spark-1.3"
        val lineup = catalogued(
            lineupOf(
                provider("claude", rows = listOf(ModelEntry(opus, "Opus", contextWindow = 200_000))) to
                    listOf(model("claude", opus)),
                provider(
                    "muse",
                    rows = listOf(ModelEntry(muse, contextWindow = 1_000_000)),
                    baseUrl = "https://api.meta.ai/v1",
                ) to listOf(model("muse", muse)),
            ),
        )
        val catalog = DoorCatalog(lineup)
        val discovery = catalog.discovery(surface = ModelDiscovery.DESKTOP).getValue("data").jsonArray
        val configured = catalog.inferenceModels(null).map { it.jsonObject }

        assertEquals(discovery.map { it.jsonObject.getValue("id") }, configured.map { it.getValue("name") })
        val row = configured.single { it.getValue("name").jsonPrimitive.content.endsWith(muse) }
        assertEquals("claude-opus-5-5", row.getValue("behavesAs").jsonPrimitive.content)
        assertEquals("max", row.getValue("maxEffort").jsonPrimitive.content)
    }

    @Test
    fun `reasoning without named effort levels does not invent an effort selector`() {
        val fact = CatalogModel(provider = "work", id = "model", label = "Model", reasons = true)
        val model = ServedModel(fact, provider("work"), listOf(model("work", "model")), claude = null)

        val discovered = DoorModelWire(AnthropicTiers(), emptyMap()).discovered(model, "work--model")

        assertEquals("true", discovered.getValue("supports_reasoning").jsonPrimitive.content)
        assertNull(discovered["thinking"])
        assertNull(discovered["capabilities"])
        val settings = DesktopModelSettings(AnthropicTiers(), mapOf("opus" to "claude-opus-5-5"))
            .models(listOf(model)).single().jsonObject
        assertNull(settings["behavesAs"])
        assertNull(settings["maxEffort"])
    }

    @Test
    fun `a provider's declared effort vocabulary reaches Desktop's reasoning control and discovery`() {
        val glm = "GLM-5.3-Flash"
        val opus = "claude-opus-5-5"
        val vocabulary = ChatEffortVocabularyConfig(
            default = "high",
            levels = mapOf("low" to "low", "medium" to "high", "high" to "high", "xhigh" to "max", "max" to "max"),
            modelPattern = "^GLM-5[.]3-Flash",
        )
        val lineup = catalogued(
            lineupOf(
                provider("claude", rows = listOf(ModelEntry(opus, "Opus", contextWindow = 200_000))) to
                    listOf(model("claude", opus)),
                provider(
                    "realinfer",
                    rows = listOf(ModelEntry(glm, contextWindow = 491_520)),
                    vocabulary = vocabulary,
                ) to listOf(model("realinfer", glm)),
            ),
        )
        val catalog = DoorCatalog(lineup)
        val row = catalog.discovery().getValue("data").jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content.endsWith(glm) }.jsonObject
        val options = row.getValue("thinking").jsonObject.getValue("effort_options").jsonArray.map { it.jsonObject }

        assertEquals(listOf("low", "high", "max"), options.map { it.getValue("id").jsonPrimitive.content })
        val recommended = options.single { it.getValue("id").jsonPrimitive.content == "high" }
        assertEquals("true", recommended.getValue("recommended").jsonPrimitive.content)
        val settings = catalog.inferenceModels(null).map { it.jsonObject }
            // Desktop receives the row under its readable name: the lab's word put another way (ClientModelNames).
            .single { it.getValue("name").jsonPrimitive.content.endsWith("zai-5.3-Flash") }
        assertEquals("claude-opus-5-5", settings.getValue("behavesAs").jsonPrimitive.content)
        assertEquals("max", settings.getValue("maxEffort").jsonPrimitive.content)
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
        vocabulary: ChatEffortVocabularyConfig? = null,
    ): ServedProvider = FakeProvider(
        key,
        ModelCatalog(
            discoveryPrefix = "$key--",
            models = rows,
            defaultContextWindow = 200_000,
            vendorBaseUrl = baseUrl,
        ),
        login,
        vocabulary,
    )

    /** A provider whose `model_slots` maps [tiers], listing the default row. */
    private fun mapping(key: String, tiers: Map<String, String>): ServedProvider =
        FakeProvider(key, (provider(key) as FakeProvider).catalog, usesLogin = false, tiers = tiers)

    private fun model(provider: String, id: String, discovered: Boolean = false): DoorModel =
        DoorModel("$provider--$id", id, provider, id, discovered = discovered)
}

/** A provider the door reads, with nothing behind it: it is read for its catalog and never asked to serve a turn. */
private class FakeProvider(
    override val key: String,
    override val catalog: ModelCatalog,
    override val usesLogin: Boolean,
    override val effortVocabulary: ChatEffortVocabularyConfig? = null,
    override val tiers: Map<String, String> = emptyMap(),
) :
    ServedProvider {
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
