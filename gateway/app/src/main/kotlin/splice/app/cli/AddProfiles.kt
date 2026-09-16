// NEW: v0.4.0 FEATURES.md §1 — the profiles `splice add` knows — one per auth-kind/dialect pair
// Topology already validates — as DATA: the wire shape, the default head and wrapper command, the
// starter models. The command asks the operator only for what a profile cannot know (a base URL
// for a generic OpenAI-compatible endpoint, models where the profile ships none), and this class
// renders the two TOML tables that are APPENDED to the operator's file, never merged into it.
package splice.app.cli

import splice.core.model.ModelRates

private const val WINDOW_200K = 200_000L
private const val WINDOW_262K = 262_144L
private const val ANTHROPIC_PASSTHROUGH = "anthropic-passthrough"
private const val API_KEY = "api-key"
private const val WINDOW_272K = 272_000L
private const val WINDOW_400K = 400_000L
private const val WINDOW_500K = 500_000L
private const val WINDOW_1M = 1_000_000L
private const val WINDOW_1048K = 1_048_576L
private const val WINDOW_1050K = 1_050_000L
private const val WINDOW_1310K = 1_310_720L
private const val OPENAI_CHAT = "openai-chat"

// V4-35: the content blocks DeepSeek's Anthropic-compatibility table marks Supported.
// Everything absent here — redacted_thinking, image, document, search_result, mcp_tool_use,
// mcp_tool_result, container_upload, code_execution_tool_result — they reject.
// ORACLE 2026-09-16: NOT a reading of DeepSeek's compatibility table — the table says what is
// SUPPORTED, which is a different claim from what is REJECTED, and reading the first as the second
// is how image and document spent a campaign being dropped. This is the endpoint's own accepted set,
// read out of its deserializer by POSTing an unknown variant: it answers `unknown variant X,
// expected one of ...` and names all nine. Re-probe rather than edit from docs.
private val DEEPSEEK_BLOCKS = listOf(
    "text",
    "tool_reference",
    "image",
    "document",
    "server_tool_use",
    "tool_use",
    "tool_result",
    "web_search_tool_result",
    "thinking",
).joinToString { block -> "\"" + block + "\"" }

/** One model row; [slots] are the Claude model slots a passthrough head maps it to (fable/opus/...). */
internal data class AddModel(
    val id: String,
    val label: String,
    val contextWindow: Long,
    val slots: List<String> = emptyList(),
    /** V4-37: this row's rate card, USD per million tokens, so a freshly added head prices its own
     *  spend instead of the client's Anthropic-priced figure. Null = no card, and the statusline
     *  renders the client's number exactly as it did before. */
    val rates: ModelRates? = null,
) {
    /** The rate card emitted INLINE on the model row — never as a `[providers.X.models.rates]`
     *  sub-table. That spelling repeats its header once per model, and splice's own
     *  TomlStructurePreflight.rejectDuplicateModelKeys refuses a repeated single-bracket header
     *  ("defined twice ... ktoml silently merges both bodies"), so it is a hard parse error for any
     *  provider with more than one model. Inline is the only form that survives. */
    internal fun ratesLine(): List<String> = rates?.let { r ->
        val write = r.cacheWrite?.let { w -> ", cache_write = $w" }.orEmpty()
        listOf("rates = { input = ${r.input}, cache_read = ${r.cacheRead}, output = ${r.output}$write }")
    } ?: emptyList()
}

internal data class AddProfile(
    val name: String,
    val summary: String,
    val dialect: String,
    val authKind: String,
    /** Null when the operator must supply it (`--base-url`). */
    val baseUrl: String?,
    /** Default provider AND head key; empty when the operator must name it (`--name`). */
    val headKey: String,
    /** Default wrapper command; empty means `claude-<key>`. */
    val command: String,
    val models: List<AddModel>,
    /** Extra provider lines, already valid TOML (a default vendor header, for one). */
    val providerExtra: List<String> = emptyList(),
)

internal class AddProfiles {

    private val profiles = listOf(
        AddProfile(
            name = "codex",
            summary = "ChatGPT subscription over the Responses API (browser sign-in)",
            dialect = "openai-responses",
            authKind = "chatgpt-oauth",
            baseUrl = "https://chatgpt.com/backend-api/codex",
            headKey = "codex",
            command = "claudex",
            models = listOf(
                AddModel("gpt-5.6-sol", "GPT-5.6 Sol", WINDOW_400K),
                AddModel("gpt-5.5", "GPT-5.5", WINDOW_272K),
                AddModel("gpt-5.4-mini", "GPT-5.4 mini", WINDOW_272K),
            ),
        ),
        AddProfile(
            name = "grok",
            summary = "xAI SuperGrok subscription over the Responses API (browser sign-in)",
            dialect = "openai-responses",
            authKind = "grok-oauth",
            baseUrl = "https://api.x.ai/v1",
            headKey = "grok",
            command = "claude-grok",
            models = listOf(
                AddModel("grok-4.6", "Grok 4.6", WINDOW_500K),
                AddModel("grok-4.5", "Grok 4.5", WINDOW_500K),
            ),
        ),
        AddProfile(
            name = "kimi",
            summary = "Moonshot Kimi subscription over the Anthropic wire (device sign-in)",
            dialect = ANTHROPIC_PASSTHROUGH,
            authKind = "kimi-oauth",
            baseUrl = "https://api.kimi.com/coding",
            headKey = "kimi",
            command = "claude-kimi",
            models = listOf(
                AddModel("k3-256k", "Kimi K3 256k", WINDOW_262K),
                AddModel("k3[1m]", "Kimi K3 (1M)", WINDOW_1M),
                AddModel("kimi-for-coding", "Kimi for Coding", WINDOW_262K),
            ),
        ),
        AddProfile(
            name = "muse",
            summary = "Meta Muse subscription over the Anthropic wire (device sign-in)",
            dialect = ANTHROPIC_PASSTHROUGH,
            authKind = "muse-oauth",
            baseUrl = "https://api.meta.ai",
            headKey = "muse",
            command = "claude-muse",
            models = listOf(
                AddModel("muse-spark-1.3[1m]", "Muse Spark 1.3", WINDOW_1M),
                AddModel("muse-spark-1.2[1m]", "Muse Spark 1.2", WINDOW_1M),
            ),
        ),
        AddProfile(
            // V4-35: DeepSeek publish an Anthropic-format endpoint, so this rides the dialect we
            // already have — no new code, only the quirks naming the blocks they reject.
            //
            // SLOTS: exactly one per id, because modelsFor rejects a head whose model list repeats
            // an id and the flatMap below emits one row per slot. haiku and fable are deliberately
            // undeclared — DeepSeek publish two model ids, and the documented law here is that one
            // honest pre-upstream 400 beats a duplicated picker row. Background titling on this
            // head 400s cheaply and the main turn is unaffected.
            //
            // deepseek-flash takes OPUS, not v4-pro. It serves DeepSeek-V4.1-Flash, and DeepSeek's
            // own docs say it "comprehensively surpassed V4 Pro in performance, cost, speed and
            // total time". The name says pro is the strong one; the vendor's own evidence says the
            // opposite, and the evidence wins.
            name = "deepseek",
            summary = "DeepSeek over its Anthropic-format endpoint (API key)",
            dialect = ANTHROPIC_PASSTHROUGH,
            authKind = API_KEY,
            baseUrl = "https://api.deepseek.com/anthropic",
            headKey = "deepseek",
            command = "claude-deepseek",
            // V4-37 RATES: DeepSeek publishes peak and OFF-PEAK cards; we declare OFF-PEAK, because
            // averaging the two would invent a price DeepSeek does not charge (see TokenCost.kt).
            // Peak is exactly 2x these, kept here so the choice is visible, not buried:
            //   flash   peak 0.30 / 0.006 / 1.20      pro  peak 1.32 / 0.044 / 3.96
            // No cache_write bucket — this endpoint reports none, so those tokens bill at input.
            models = listOf(
                AddModel(
                    "deepseek-flash",
                    "DeepSeek V4.1 Flash",
                    WINDOW_1M,
                    slots = listOf("opus"),
                    rates = ModelRates(input = 0.15, cacheRead = 0.003, output = 0.60),
                ),
                AddModel(
                    "deepseek-v4-pro",
                    "DeepSeek V4 Pro",
                    WINDOW_1M,
                    slots = listOf("sonnet"),
                    rates = ModelRates(input = 0.66, cacheRead = 0.022, output = 1.98),
                ),
            ),
            providerExtra = listOf(
                "[providers.deepseek.quirks]",
                "# Only the blocks DeepSeek's compatibility table marks Supported. redacted_thinking",
                "# is the one that matters: DR-118 forwards it VERBATIM because Anthropic demands it",
                "# back unchanged, so without this allowlist every replayed signed-thinking turn",
                "# carries a block DeepSeek refuse.",
                "block_allowlist = [" + DEEPSEEK_BLOCKS + "]",
                "# cache_control is ignored upstream, so sending it is pure wire weight.",
                "strip_cache_control = true",
            ),
        ),
        AddProfile(
            // V4-34: OpenRouter was missing from this catalog, so `splice add openrouter` did not
            // exist and the starter shipped one unslotted Haiku row — Claude Code emitted no tier
            // picker. Ten rows from GET openrouter.ai/api/v1/models?sort=most-popular on 2026-09-16;
            // exactly one slot per id (modelsFor rejects duplicates).
            //
            // sonnet = anthropic/claude-sonnet-5: daily driver, pinned.
            // opus = anthropic/claude-opus-5: strongest Claude on the listing.
            // haiku = z-ai/glm-5.3-flash: cheapest/fastest among the top-weekly coding-capable rows.
            // fable = openai/gpt-5.6-sol: strongest GPT-class row on the same listing.
            name = "openrouter",
            summary = "OpenRouter API-key route (many vendors, one key)",
            dialect = OPENAI_CHAT,
            authKind = API_KEY,
            baseUrl = "https://openrouter.ai/api/v1",
            headKey = "openrouter",
            command = "claude-openrouter",
            models = listOf(
                AddModel("anthropic/claude-sonnet-5", "Claude Sonnet 5", WINDOW_1M, listOf("sonnet")),
                AddModel("anthropic/claude-opus-5", "Claude Opus 5", WINDOW_1M, listOf("opus")),
                AddModel("z-ai/glm-5.3-flash", "GLM 5.3 Flash", WINDOW_1310K, listOf("haiku")),
                AddModel("openai/gpt-5.6-sol", "GPT-5.6 Sol", WINDOW_1050K, listOf("fable")),
                AddModel("openai/gpt-5.6-luna", "GPT-5.6 Luna", WINDOW_1050K),
                AddModel("google/gemini-3.8-flash", "Gemini 3.8 Flash", WINDOW_1048K),
                AddModel("deepseek/deepseek-v4-flash-0731", "DeepSeek V4 Flash 0731", WINDOW_1310K),
                AddModel("z-ai/glm-5.3", "GLM 5.3", WINDOW_1310K),
                AddModel("meta-llama/llama-4-maverick", "Llama 4 Maverick", WINDOW_1048K),
                AddModel("anthropic/claude-haiku-4.5", "Claude Haiku 4.5", WINDOW_200K),
            ),
        ),
        AddProfile(
            name = "claude",
            summary = "Anthropic with your own Claude login, forwarded untouched",
            dialect = ANTHROPIC_PASSTHROUGH,
            authKind = "client",
            baseUrl = "https://api.anthropic.com",
            headKey = "claude-splice",
            command = "claude-splice",
            models = listOf(
                AddModel("claude-fable-5", "Claude Fable 5", WINDOW_200K, listOf("fable")),
                AddModel("claude-opus-5", "Claude Opus 5", WINDOW_200K, listOf("opus")),
                AddModel("claude-sonnet-5", "Claude Sonnet 5", WINDOW_200K, listOf("sonnet")),
                AddModel("claude-haiku-4-5", "Claude Haiku 4.5", WINDOW_200K, listOf("haiku")),
            ),
            providerExtra = listOf("""extra_headers = { anthropic-version = "2023-06-01" }"""),
        ),
        AddProfile(
            name = "api-key",
            summary = "any OpenAI-compatible chat endpoint with an API key (OpenRouter, Fireworks, a local runtime)",
            dialect = "openai-chat",
            authKind = API_KEY,
            baseUrl = null,
            headKey = "",
            command = "",
            models = emptyList(),
        ),
    )

    fun find(name: String): AddProfile? = profiles.firstOrNull { it.name == name }

    fun describe(): List<String> = profiles.map { "${it.name.padEnd(NAME_PAD)} ${it.summary}" }

    /** Every profile `splice add` knows. The wizard ticks from this list, never a typed roster. */
    fun catalog(): List<AddProfile> = profiles

    /** The env var an api-key provider reads: the key upper-cased with dashes as underscores. */
    fun apiKeyEnv(key: String): String = key.uppercase().replace('-', '_') + "_API_KEY"

    /** One provider table and one head table for [key], appended verbatim; every value quoted.
     *  [profile] is the RESOLVED one: base URL, command and models already filled in by the command. */
    fun toml(profile: AddProfile, key: String, port: Int): String {
        val models = profile.models
        val auth = if (profile.authKind == API_KEY) {
            """auth = { kind = "api-key", env = "${apiKeyEnv(key)}" }"""
        } else {
            """auth = { kind = "${profile.authKind}" }"""
        }
        val provider = listOf(
            "",
            "# Added by `splice add ${profile.name}`.",
            "[providers.$key]",
            "dialect = \"${profile.dialect}\"",
            "base_url = \"${profile.baseUrl.orEmpty()}\"",
            auth,
        ) + profile.providerExtra + models.flatMap { m ->
            listOf(
                "[[providers.$key.models]]",
                "id = \"${m.id}\"",
                "label = \"${m.label}\"",
                "context_window = ${m.contextWindow}",
            ) + m.ratesLine()
        }
        val mappings = models.flatMap { m -> m.slots.map { slot -> m.id to slot } }
        val pinned = models.first()
        val head = listOf(
            "",
            "[heads.$key]",
            "provider = \"$key\"",
            "port = $port",
            "discovery_prefix = \"claude-$key--\"",
            "pinned_model = \"${pinned.id}\"",
        ) + headExtras(mappings, pinned.contextWindow) + listOf(
            "[heads.$key.claude]",
            "command = \"${profile.command}\"",
        )
        return (provider + head).joinToString("\n") + "\n"
    }

    /** Head-wide window is the pinned row's ceiling, never the catalog max. Slot mappings are
     *  omitted when the profile has none, so the head keeps the provider-wide surface. */
    private fun headExtras(mappings: List<Pair<String, String>>, pinnedWindow: Long): List<String> {
        val window = listOf("context_window = $pinnedWindow")
        return if (mappings.isEmpty()) {
            window
        } else {
            listOf(
                "models = [" +
                    mappings.joinToString { (id, slot) -> "{ id = \"$id\", slot = \"$slot\" }" } + "]",
            ) + window
        }
    }
}

private const val NAME_PAD = 12
