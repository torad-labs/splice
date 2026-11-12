// NEW: the nine vendor rows `splice add` knows, as DATA. Split out of AddProfiles.kt
// (2026-09-16): widening DeepSeek's block allowlist to the endpoint's real accepted set pushed that
// file from 2.98 to 3.09 and the concentration wall put it in the HIGH band. The rows are the bulk
// and they are pure data, so they are what moves; AddProfiles keeps the lookup and the TOML render.
package splice.configuration.add

import splice.core.model.LongContextRates
import splice.core.model.ModelRates

private const val WINDOW_200K = 200_000L
private const val WINDOW_262K = 262_144L
private const val ANTHROPIC_PASSTHROUGH = "anthropic-passthrough"
private const val OPENAI_RESPONSES = "openai-responses"

/** Shared with AddProfiles.kt across the 2026-09-16 split. */
internal const val API_KEY = "api-key"
private const val WINDOW_272K = 272_000L

// why: grok-build-0.1's maxPromptLength on docs.x.ai (V4-224)
private const val WINDOW_256K = 256_000L

private const val WINDOW_500K = 500_000L

// why: gpt-6-astra's max_context_window on the ChatGPT backend's own model listing (V4-224)
private const val WINDOW_872K = 872_000L

private const val WINDOW_1M = 1_000_000L
private const val WINDOW_1048K = 1_048_576L
private const val WINDOW_1050K = 1_050_000L
private const val OPENAI_CHAT = "openai-chat"
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

/** The vendor rows, one per auth-kind/dialect pair. A class rather than a top-level val so the
 *  catalogue has a name the wizard and `splice add` both reach for. */
internal class AddProfileCatalog {

    val rows: List<AddProfile> = listOf(
        AddProfile(
            name = "codex",
            baseUrl = "https://chatgpt.com/backend-api/codex",
            provider = AddProviderSpec(
                dialect = "openai-responses",
                authKind = "chatgpt-oauth",
            ),
            head = AddHeadSpec(
                key = "codex",
                command = "claudex",
            ),
            labels = AddLabels(
                summary = "ChatGPT subscription over the Responses API (browser sign-in)",
            ),
            // V4-224: what the ChatGPT backend serves, windowed by its own model listing
            // (chatgpt.com/backend-api/codex/models, read 2026-09-25): context_window 272000 on every row,
            // and gpt-6-astra's max_context_window 872000, the vendor's sanctioned opt-in (measured served at
            // 637k tokens on 2026-09-21, UpstreamRoster). OpenAI's Codex docs (developers.openai.com/codex/
            // models) name gpt-6-sol the model to choose, so it is pinned. The API's 1,050,000 for the same id
            // (developers.openai.com/api/docs/models/gpt-6-sol) is the platform's window, not this backend's.
            // GPT-6 has no Terra, so gpt-5.6-terra is the row a slotted head gives its sonnet tier.
            //
            // V4-240 RATES: gpt-6-sol's API card, developers.openai.com/api/docs/models/gpt-6-sol (read
            // 2026-09-25): input $2.00, cached input $0.20, cache writes $2.50, output $10.00 per 1M tokens,
            // and "Prompts with more than 272K input tokens are priced at 2x input and cache rates and 1.5x
            // output for the full request". This backend's 272000 window keeps a turn at or under that
            // line today; the card carries the tier so a wider window prices right. A ChatGPT
            // subscription bills none of this per token: the figure is what the turns would cost at the API.
            models = listOf(
                AddModel(
                    "gpt-6-sol",
                    "GPT-6 Sol",
                    WINDOW_272K,
                    rates = ModelRates(
                        input = 2.0,
                        cacheRead = 0.2,
                        output = 10.0,
                        cacheWrite = 2.5,
                        longContext = LongContextRates(
                            overInputTokens = 272_000,
                            input = 4.0,
                            cacheRead = 0.4,
                            output = 15.0,
                            cacheWrite = 5.0,
                        ),
                    ),
                ),
                AddModel("gpt-6-astra", "GPT-6 Astra", WINDOW_872K),
                AddModel("gpt-5.6-terra", "GPT-5.6 Terra", WINDOW_272K),
                AddModel("gpt-6-luna", "GPT-6 Luna", WINDOW_272K),
            ),
        ),
        AddProfile(
            name = "grok",
            baseUrl = "https://api.x.ai/v1",
            provider = AddProviderSpec(
                dialect = "openai-responses",
                authKind = "grok-oauth",
            ),
            head = AddHeadSpec(
                key = "grok",
                command = "claude-grok",
            ),
            labels = AddLabels(
                summary = "xAI SuperGrok subscription over the Responses API (browser sign-in)",
            ),
            // V4-224: windows from docs.x.ai/developers/models (maxPromptLength, read 2026-09-25). The build
            // row is the concrete grok-build-0.1: the endpoint's own listing resolves grok-build-latest to
            // grok-4.5 now, so an alias row would have its window move underneath it.
            //
            // V4-240 RATES: grok-4.7's API card, docs.x.ai/developers/models/grok-4.7 (read 2026-09-25):
            // under 200k prompt tokens input $2.00, cached input $0.50, output $6.00 per 1M tokens; at 200k
            // or more $4.00, $1.00 and $12.00, and "Requests whose prompt reaches 200k tokens are billed at
            // the higher rate for all tokens in the request", so the tier starts over 199,999. xAI lists
            // no cache-write price, so those tokens bill at input.
            models = listOf(
                AddModel(
                    "grok-4.7",
                    "Grok 4.7",
                    WINDOW_500K,
                    rates = ModelRates(
                        input = 2.0,
                        cacheRead = 0.5,
                        output = 6.0,
                        longContext = LongContextRates(
                            overInputTokens = 199_999,
                            input = 4.0,
                            cacheRead = 1.0,
                            output = 12.0,
                        ),
                    ),
                ),
                AddModel("grok-4.6", "Grok 4.6", WINDOW_500K),
                AddModel("grok-build-0.1", "Grok Build 0.1", WINDOW_256K),
            ),
        ),
        AddProfile(
            name = "kimi",
            baseUrl = "https://api.kimi.com/coding",
            provider = AddProviderSpec(
                dialect = ANTHROPIC_PASSTHROUGH,
                authKind = "kimi-oauth",
            ),
            head = AddHeadSpec(
                key = "kimi",
                command = "claude-kimi",
            ),
            labels = AddLabels(
                summary = "Moonshot Kimi subscription over the Anthropic wire (device sign-in)",
            ),
            models = listOf(
                AddModel("k3-256k", "Kimi K3 256k", WINDOW_262K),
                AddModel("k3[1m]", "Kimi K3 (1M)", WINDOW_1M),
                AddModel("kimi-for-coding", "Kimi for Coding", WINDOW_262K),
                AddModel("kimi-for-coding-highspeed", "Kimi for Coding (high speed)", WINDOW_262K),
            ),
        ),
        AddProfile(
            name = "muse",
            baseUrl = "https://api.meta.ai/v1",
            provider = AddProviderSpec(
                dialect = OPENAI_RESPONSES,
                authKind = "muse-oauth",
            ),
            head = AddHeadSpec(
                key = "muse",
                command = "claude-muse",
            ),
            labels = AddLabels(
                summary = "Meta Muse subscription over the Responses wire (device sign-in)",
            ),
            // V4-229: Meta publishes no window, so 1.3's is measured. api.meta.ai served a prompt of
            // 1,010,789 tokens by its own count (2026-09-25 14:36 CDT, through claude-muse), past the
            // 1,000,000 declared here, so the row stays at the window Claude Code's `[1m]` implies. The
            // cold prompt met three upstream 504s ("the response stream did not start before the server
            // timeout") before the fourth attempt rode the cached prefix. 1.2's is measured the same way:
            // 1,010,789 tokens served cold on the first attempt (2026-09-25 15:00:47 CDT, 46.4 s).
            //
            // V4-240 RATES: muse-spark-1.3's Standard tier, dev.meta.ai/docs/pricing-rate-limits (read
            // 2026-09-25): input $1.25, cached input $0.15, output $4.25 per 1M tokens, and "There is no
            // long-context premium". Meta lists no cache-write price, so those tokens bill at input.
            models = listOf(
                AddModel(
                    "muse-spark-1.3[1m]",
                    "Muse Spark 1.3",
                    WINDOW_1M,
                    rates = ModelRates(input = 1.25, cacheRead = 0.15, output = 4.25),
                ),
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
            baseUrl = "https://api.deepseek.com/anthropic",
            provider = AddProviderSpec(
                dialect = ANTHROPIC_PASSTHROUGH,
                authKind = API_KEY,
                extra = listOf(
                    "# DeepSeek list their models at the API root, not under the /anthropic base this dials.",
                    "models_url = \"https://api.deepseek.com/models\"",
                    "[providers.$KEY_SLOT.quirks]",
                    "# Only the blocks DeepSeek's compatibility table marks Supported. redacted_thinking",
                    "# is the one that matters: DR-118 forwards it VERBATIM because Anthropic demands it",
                    "# back unchanged, so without this allowlist every replayed signed-thinking turn",
                    "# carries a block DeepSeek refuse.",
                    "block_allowlist = [" + DEEPSEEK_BLOCKS + "]",
                    "# cache_control is ignored upstream, so sending it is pure wire weight.",
                    "strip_cache_control = true",
                    "# A truncated stream is resumed by re-POSTing with the partial answer appended as a",
                    "# trailing assistant message; MEASURED 2026-09-16, this endpoint continues from that",
                    "# instead of restarting. Off by default because muse 400s on the same shape.",
                    "reanchor_prefill = true",
                ),
            ),
            head = AddHeadSpec(
                key = "deepseek",
                command = "claude-deepseek",
            ),
            labels = AddLabels(
                summary = "DeepSeek over its Anthropic-format endpoint (API key)",
            ),
            // RATES (Oct 10, 2026): none written. splice ships DeepSeek's off-peak card with its peak hours
            // (published-rates.tsv), and a card written here would win over it and price every hour off-peak.
            models = listOf(
                AddModel(
                    "deepseek-flash",
                    "DeepSeek V4.1 Flash",
                    WINDOW_1M,
                    slots = listOf("opus"),
                ),
                AddModel(
                    "deepseek-v4-pro",
                    "DeepSeek V4 Pro",
                    WINDOW_1M,
                    slots = listOf("sonnet"),
                ),
            ),
        ),
        AddProfile(
            // V4-34: OpenRouter was missing from this catalog, so `splice add openrouter` did not
            // exist and the starter shipped one unslotted Haiku row — Claude Code emitted no tier
            // picker. Ten rows from GET openrouter.ai/api/v1/models?sort=most-popular on 2026-09-16;
            // exactly one slot per id (modelsFor rejects duplicates).
            //
            // sonnet = anthropic/claude-sonnet-5: daily driver, pinned.
            // opus = anthropic/claude-opus-5.5: strongest Claude on the listing.
            // haiku = z-ai/glm-5.3-flash: cheapest/fastest among the top-weekly coding-capable rows.
            // fable = openai/gpt-6-sol: strongest GPT-class row on the same listing.
            //
            // V4-228: each family's latest as OpenRouter lists it, windowed by its own context_length
            // (GET openrouter.ai/api/v1/models, read 2026-09-25 through `splice models --all`). Opus 5.5
            // (1,000,000) takes opus from Opus 5, GPT-6 Sol and Luna (1,050,000) take GPT-5.6's rows, and
            // DeepSeek V4.1 Flash (1,048,576) replaces V4 Flash 0731. Sonnet 5, Haiku 4.5, Gemini 3.8
            // Flash, GLM 5.3 and 5.3 Flash, and Llama 4 Maverick are still their families' latest there.
            //
            // V4-434 RATES: every row carries OpenRouter's own card, the `pricing` block of the same listing
            // (read 2026-09-29 2:05 PM CT, 464 models), USD per token there and per 1M tokens here: prompt,
            // completion, input_cache_read and input_cache_write. OpenRouter's write price is its default
            // 5-minute one (input_cache_write_1h is the 1-hour rate), and this dialect sends no cache_control
            // and reads no write bucket from the usage, so a write bills only where OpenRouter reports one.
            // Where a card lists no write (GLM 5.3 and 5.3 Flash, DeepSeek V4.1 Flash, Llama 4 Maverick) those
            // tokens bill at input, as the Meta row does; Llama 4 Maverick lists no cache read either, so its
            // cached tokens bill at input too (cacheRead = input). Gemini 3.8 Flash's listed write price is
            // below its input price, kept as OpenRouter lists it. GPT-6 Sol and Luna list an `overrides` entry
            // at min_prompt_tokens 272000, which OpenRouter's docs say applies strictly above it, so it is
            // the long-context tier at 272,000, all four prices (their 1,050,000 window reaches it). Each
            // route bills its own card: DeepSeek V4.1 Flash here is $0.30 / $1.20, the deepseek profile's
            // direct off-peak card $0.15 / $0.60. Web search ($0.01 a call on the Claude and GPT rows) and
            // Gemini's internal_reasoning are not per-token buckets and are not in a card.
            name = "openrouter",
            baseUrl = "https://openrouter.ai/api/v1",
            provider = AddProviderSpec(
                dialect = OPENAI_CHAT,
                authKind = API_KEY,
            ),
            head = AddHeadSpec(
                key = "openrouter",
                command = "claude-openrouter",
            ),
            labels = AddLabels(
                summary = "OpenRouter API-key route (many vendors, one key)",
            ),
            policy = AddModelPolicy(
                discoverRoster = true,
            ),
            models = listOf(
                AddModel(
                    "anthropic/claude-sonnet-5",
                    "Claude Sonnet 5",
                    WINDOW_1M,
                    listOf("sonnet"),
                    rates = ModelRates(input = 2.0, cacheRead = 0.2, output = 10.0, cacheWrite = 2.5),
                ),
                AddModel(
                    "anthropic/claude-opus-5.5",
                    "Claude Opus 5.5",
                    WINDOW_1M,
                    listOf("opus"),
                    rates = ModelRates(input = 4.0, cacheRead = 0.2, output = 20.0, cacheWrite = 5.0),
                ),
                AddModel(
                    "z-ai/glm-5.3-flash",
                    "GLM 5.3 Flash",
                    WINDOW_1048K,
                    listOf("haiku"),
                    rates = ModelRates(input = 0.15, cacheRead = 0.03, output = 0.5),
                ),
                AddModel(
                    "openai/gpt-6-sol",
                    "GPT-6 Sol",
                    WINDOW_1050K,
                    listOf("fable"),
                    rates = ModelRates(
                        input = 2.0,
                        cacheRead = 0.2,
                        output = 10.0,
                        cacheWrite = 2.5,
                        longContext = LongContextRates(
                            overInputTokens = 272_000,
                            input = 4.0,
                            cacheRead = 0.4,
                            output = 15.0,
                            cacheWrite = 5.0,
                        ),
                    ),
                ),
                AddModel(
                    "openai/gpt-6-luna",
                    "GPT-6 Luna",
                    WINDOW_1050K,
                    rates = ModelRates(
                        input = 0.1,
                        cacheRead = 0.01,
                        output = 0.5,
                        cacheWrite = 0.125,
                        longContext = LongContextRates(
                            overInputTokens = 272_000,
                            input = 0.2,
                            cacheRead = 0.02,
                            output = 0.75,
                            cacheWrite = 0.25,
                        ),
                    ),
                ),
                AddModel(
                    "google/gemini-3.8-flash",
                    "Gemini 3.8 Flash",
                    WINDOW_1048K,
                    rates = ModelRates(input = 0.75, cacheRead = 0.075, output = 3.75, cacheWrite = 0.0416667),
                ),
                AddModel(
                    "deepseek/deepseek-v4.1-flash",
                    "DeepSeek V4.1 Flash",
                    WINDOW_1048K,
                    rates = ModelRates(input = 0.3, cacheRead = 0.006, output = 1.2),
                ),
                AddModel(
                    "z-ai/glm-5.3",
                    "GLM 5.3",
                    WINDOW_1048K,
                    rates = ModelRates(input = 1.4, cacheRead = 0.26, output = 4.4),
                ),
                AddModel(
                    "meta-llama/llama-4-maverick",
                    "Llama 4 Maverick",
                    WINDOW_1048K,
                    rates = ModelRates(input = 0.1875, cacheRead = 0.1875, output = 0.6525),
                ),
                AddModel(
                    "anthropic/claude-haiku-4.5",
                    "Claude Haiku 4.5",
                    WINDOW_200K,
                    rates = ModelRates(input = 1.0, cacheRead = 0.1, output = 5.0, cacheWrite = 1.25),
                ),
            ),
        ),
        AddProfile(
            name = "claude",
            baseUrl = "https://api.anthropic.com",
            provider = AddProviderSpec(
                dialect = ANTHROPIC_PASSTHROUGH,
                authKind = "client",
                extra = listOf("""extra_headers = { anthropic-version = "2023-06-01" }"""),
            ),
            head = AddHeadSpec(
                key = "claude-splice",
                command = "claude-splice",
            ),
            labels = AddLabels(
                summary = "Anthropic with your own Claude login, forwarded untouched",
            ),
            // THE HEAD NAMES NO MODEL (Marcos, Nov 9, 2026, of his own Claude head: "for default anthropic claude
            // it should juyst show the default models iot shouldn't list anything otherwise when new models like
            // this comes up wi'll miss them"). Until Nov 11, 2026 this profile pinned claude-fable-5-1 and listed
            // Fable 5.1, Opus 5.5, Sonnet 5 and Haiku 4.5 by tier, so a new install started on Fable 5.1 and
            // never showed Fable 5.2, Sonnet 5.5 or Haiku 5.5, on a machine whose own Claude sessions ran
            // claude-fable-5-2 for 85 in 100 requests. The policy below writes neither line: Claude Code shows
            // its own models and starts on its own default.
            //
            // THE ROWS only declare the current models' names and windows, for the Models page and
            // `splice models`: platform.claude.com/docs/en/about-claude/models/overview and the pricing page
            // (read Nov 11, 2026) give Fable 5.2, Opus 5.5, Sonnet 5.5 and Haiku 5.5 a 1M window. A model that is
            // not a row is served all the same, since the client picks (ProviderConfig.clientPicksModels).
            //
            // RATES: none written, as on DeepSeek's rows. splice ships Anthropic's published card for each model
            // (published-rates.tsv), a turn on a model no row names is priced from it too (TurnPrice), and a
            // card written here would win over it and keep a new install on the day's prices for good. The rows
            // carried their own cards from before that table existed (V4-240, V4-270).
            policy = AddModelPolicy(clientRoster = true),
            models = listOf(
                AddModel("claude-fable-5-2", "Claude Fable 5.2", WINDOW_1M),
                AddModel("claude-opus-5-5", "Claude Opus 5.5", WINDOW_1M),
                AddModel("claude-sonnet-5-5", "Claude Sonnet 5.5", WINDOW_1M),
                AddModel("claude-haiku-5-5", "Claude Haiku 5.5", WINDOW_1M),
            ),
        ),
        AddProfile(
            name = "local",
            baseUrl = null,
            provider = AddProviderSpec(
                dialect = OPENAI_CHAT,
                authKind = API_KEY,
                requiresKey = false,
            ),
            head = AddHeadSpec(
                key = "",
                command = "",
            ),
            labels = AddLabels(
                summary = "a local OpenAI-compatible model endpoint (no API key)",
            ),
            policy = AddModelPolicy(
                listAuthoritative = false,
            ),
            models = emptyList(),
        ),
        AddProfile(
            name = "api-key",
            baseUrl = null,
            provider = AddProviderSpec(
                dialect = "openai-chat",
                authKind = API_KEY,
            ),
            head = AddHeadSpec(
                key = "",
                command = "",
            ),
            labels = AddLabels(
                summary = "any OpenAI-compatible chat endpoint with an API key (OpenRouter, Fireworks)",
            ),
            models = emptyList(),
        ),
    )
}
