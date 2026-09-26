// NEW: the eight vendor rows `splice add` knows, as DATA. Split out of AddProfiles.kt
// (2026-09-16): widening DeepSeek's block allowlist to the endpoint's real accepted set pushed that
// file from 2.98 to 3.09 and the concentration wall put it in the HIGH band. The rows are the bulk
// and they are pure data, so they are what moves; AddProfiles keeps the lookup and the TOML render.
package splice.configuration.add

import splice.core.model.LongContextRates
import splice.core.model.ModelRates

private const val WINDOW_200K = 200_000L
private const val WINDOW_262K = 262_144L
private const val ANTHROPIC_PASSTHROUGH = "anthropic-passthrough"

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
private const val WINDOW_1310K = 1_310_720L
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
            summary = "ChatGPT subscription over the Responses API (browser sign-in)",
            dialect = "openai-responses",
            authKind = "chatgpt-oauth",
            baseUrl = "https://chatgpt.com/backend-api/codex",
            headKey = "codex",
            command = "claudex",
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
            summary = "xAI SuperGrok subscription over the Responses API (browser sign-in)",
            dialect = "openai-responses",
            authKind = "grok-oauth",
            baseUrl = "https://api.x.ai/v1",
            headKey = "grok",
            command = "claude-grok",
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
                AddModel("kimi-for-coding-highspeed", "Kimi for Coding (high speed)", WINDOW_262K),
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
                "# A truncated stream is resumed by re-POSTing with the partial answer appended as a",
                "# trailing assistant message; MEASURED 2026-09-16, this endpoint continues from that",
                "# instead of restarting. Off by default because muse 400s on the same shape.",
                "reanchor_prefill = true",
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
            name = "openrouter",
            summary = "OpenRouter API-key route (many vendors, one key)",
            dialect = OPENAI_CHAT,
            authKind = API_KEY,
            baseUrl = "https://openrouter.ai/api/v1",
            headKey = "openrouter",
            command = "claude-openrouter",
            models = listOf(
                AddModel("anthropic/claude-sonnet-5", "Claude Sonnet 5", WINDOW_1M, listOf("sonnet")),
                AddModel("anthropic/claude-opus-5.5", "Claude Opus 5.5", WINDOW_1M, listOf("opus")),
                AddModel("z-ai/glm-5.3-flash", "GLM 5.3 Flash", WINDOW_1310K, listOf("haiku")),
                AddModel("openai/gpt-6-sol", "GPT-6 Sol", WINDOW_1050K, listOf("fable")),
                AddModel("openai/gpt-6-luna", "GPT-6 Luna", WINDOW_1050K),
                AddModel("google/gemini-3.8-flash", "Gemini 3.8 Flash", WINDOW_1048K),
                AddModel("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", WINDOW_1048K),
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
            // V4-224: platform.claude.com/docs/en/models/overview (read 2026-09-25): Fable 5.1, Opus 5.5 and
            // Sonnet 5 have a 1M context window, Haiku 4.5 200K. A forwarded subscription login reaches 1M too:
            // "On the Anthropic API, Fable 5.1, Fable 5, Sonnet 5, and Opus 4.7 and later run with the 1M
            // window on every plan, including Pro" (code.claude.com/docs/en/model-config).
            //
            // V4-240 RATES: Opus 5.5's API card, platform.claude.com/docs/en/about-claude/pricing (read
            // 2026-09-25): input $4, cache hits $0.20, output $20 per 1M tokens, 5-minute cache writes $5
            // and 1-hour writes $8, and Claude 4.6 and later carry "the full 1M token context window at
            // standard pricing", so no tier. Writes are declared at the 1-hour rate: Claude Code asks for
            // the 1-hour cache on a subscriber's login (the pinned 2.1.282's bundle: ttl "1h", reason
            // "subscriber"), and this profile forwards that login.
            //
            // V4-270 RATES: the other three rows' cards from the same page (read 2026-09-25), per 1M tokens as
            // input / cache hits / 1-hour writes / output: Fable 5.1 $10 / $0.25 / $20 / $50, Sonnet 5
            // $2 / $0.20 / $4 / $10 (its launch price, which the page says "is now the standard price"), and
            // Haiku 4.5 $1 / $0.10 / $2 / $5. Writes at the 1-hour rate, as for Opus 5.5. No tier: Fable 5.1
            // and Sonnet 5 are Claude 4.6 and later, and Haiku 4.5's window is 200K. Claude Code takes its
            // small-fast turns on Haiku 4.5, so without these a default install counted them unpriced.
            models = listOf(
                AddModel(
                    "claude-fable-5-1",
                    "Claude Fable 5.1",
                    WINDOW_1M,
                    listOf("fable"),
                    rates = ModelRates(input = 10.0, cacheRead = 0.25, output = 50.0, cacheWrite = 20.0),
                ),
                AddModel(
                    "claude-opus-5-5",
                    "Claude Opus 5.5",
                    WINDOW_1M,
                    listOf("opus"),
                    rates = ModelRates(input = 4.0, cacheRead = 0.2, output = 20.0, cacheWrite = 8.0),
                ),
                AddModel(
                    "claude-sonnet-5",
                    "Claude Sonnet 5",
                    WINDOW_1M,
                    listOf("sonnet"),
                    rates = ModelRates(input = 2.0, cacheRead = 0.2, output = 10.0, cacheWrite = 4.0),
                ),
                AddModel(
                    "claude-haiku-4-5",
                    "Claude Haiku 4.5",
                    WINDOW_200K,
                    listOf("haiku"),
                    rates = ModelRates(input = 1.0, cacheRead = 0.1, output = 5.0, cacheWrite = 2.0),
                ),
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
}
