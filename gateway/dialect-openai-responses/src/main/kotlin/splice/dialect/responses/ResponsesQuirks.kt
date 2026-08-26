// NEW: the finite quirk surface separating codex / xai / openai-platform on this dialect, split out
// of ResponsesRequestBuilder.kt (2026-08-17, concentration campaign) so the module's most widely
// consumed type (20 files, 10 outside this module) is not read from inside one of its many
// consumers. Every member kept its identical name and argument list.
package splice.dialect.responses

/** The finite quirk surface separating codex / xai / openai-platform on this dialect. */
public data class ResponsesQuirks(
    val providerTag: String, // rides honest omission markers: "[image omitted by <tag> proxy: ...]"
    val store: Boolean = false,
    val cacheKeyStrategy: CacheKeyStrategy = CacheKeyStrategy.FIRST_MESSAGE_HASH,
    val effortLadder: EffortLadder = EffortLadder.CODEX,
    val supportsSummary: Boolean = true,
    val summaryRejectModelRegex: Regex? = Regex("spark", RegexOption.IGNORE_CASE),
    /** gpt-5.4-mini's ceiling is xhigh — the backend 400s effort=max on it (observed 2026-07-19). */
    val effortMaxRejectModelRegex: Regex? = Regex("mini", RegexOption.IGNORE_CASE),
    /** codex-rs parity (read from source 2026-07-19): the gpt-5.6 family is served "responses-lite".
     *  Lite turns (non-compact): instructions ride as a developer input item (top-level field
     *  omitted), tools ride as an additional_tools input item (top-level field omitted),
     *  parallel_tool_calls is FORCED false (splice omitting it left the backend default parallel ON
     *  — a sequential-tool model spraying 30-50 parallel Task calls), reasoning.context=all_turns,
     *  and the x-openai-internal-codex-responses-lite header rides. Shape accepted by the live
     *  backend (direct probe 2026-07-19: 200, correct tool call). */
    val responsesLiteModelRegex: Regex? = Regex("gpt-5\\.6", RegexOption.IGNORE_CASE),
    val compactEffortPin: String? = null, // null = inherit session effort (the cache law)
    /** The VALUE sent for parallel_tool_calls on responses-lite turns (the field itself always
     *  rides — a lite request without it 400s). codex-rs reads this per model from
     *  `model_info.supports_parallel_tool_calls` rather than hardcoding it, so it is a knob here
     *  too. Default false = today's behaviour. Turning it on lets the model batch tool calls in one
     *  turn instead of one per turn; measured 2026-07-31, claudex averages 386 output tokens/turn
     *  against grok's 663 and kimi's 786, i.e. ~2x the round-trips, and every round-trip re-sends
     *  the whole context. UNTESTED against the live backend — see the 30-50 parallel Task spray in
     *  this class's header, which came from omitting the field entirely. */
    val liteParallelToolCalls: Boolean = false,
    /** codex parity: `text.verbosity` on lite turns. codex-cli 0.145.0 sends "low"; null omits. */
    val liteTextVerbosity: String? = "low",
    /** codex parity: send a client_metadata block identifying SPLICE (never codex). Off = omitted. */
    val sendClientMetadata: Boolean = true,
    /** ws-transport WS-3: serve rounds over the Responses WebSocket, with previous_response_id
     *  chaining, falling back to SSE on ANY failure. DEFAULT FALSE — the overlay must be invisible
     *  until an operator opts in, and with it off no WebSocket is ever constructed. */
    val webSocket: Boolean = false,
    val emitToolChoice: Boolean = false,
    /** Passes through a tool's own `strict == true` as `"strict": true`; false (the default,
     *  and the only value that has ever mattered — Claude Code's ToolDefinition.strict is always
     *  null) omits the field entirely. Distinct from [forceStrictFalse] below (review 2026-07-24:
     *  conflating the two silently changed grok's live wire bytes when this feature landed). */
    val emitStrict: Boolean = false,
    /** codex-rs parity: hard-sets `strict:false` on EVERY function tool object regardless of the
     *  tool's own value (responses_api.rs:29-32; OpenCode does the same, marked "Codex parity").
     *  false (the default) leaves [emitStrict]'s pass-through behavior as the only effect, exactly
     *  today's behavior. Only CodexProvider sets this true. */
    val forceStrictFalse: Boolean = false,
    /** codex-rs parity (tools byte-parity 2026-08-26): run every function tool's input_schema
     *  through the ToolSchemaNormalize.kt pipeline — sanitize, prune unreachable $defs, compact
     *  >5KB schemas, drop unknown keywords, alphabetize properties — exactly what codex does before
     *  ANY tool rides its wire (tools/src/json_schema.rs parse_tool_input_schema). gpt-5.6 never
     *  sees a verbatim client schema from its own CLI. false (the default) = today's verbatim
     *  passthrough; only CodexProvider sets this true. */
    val normalizeToolSchemas: Boolean = false,
    /** RC-5 (reasoning-cache 2026-07-24): gateway-held reasoning continuity for tool
     *  round-trips (codex parity — repeated tool calls / duplicated reasoning without it).
     *  Off restores the pre-cache amnesia behavior exactly. */
    val reasoningCache: Boolean = true,
    /** Loop guard (2026-07-26): a stateless circuit breaker for the identical-failed-call
     *  pathology (measured on the live codex head: the same Edit re-issued 89-101x against the
     *  harness staleness guard). From the 3rd identical failure the result's output gains an
     *  escalating directive; success or changed arguments reset. Off restores plain passthrough. */
    val loopGuard: Boolean = true,
    /** Deferred tool surface (tool_search) for responses-lite turns. NULL = off, and off is the
     *  shipped default for every provider — the request is byte-identical to today. */
    val toolSurface: ToolDeferralPolicy? = null,
    /** stream_options.reasoning_summary_delivery, sent only when a summary is requested. The
     *  ChatGPT backend serves ~2.3x more titled summary sections with "sequential_cutoff"
     *  (probed 2026-07-19: 30 parts/1546ch vs 14/646 on the same prompt) — the same value
     *  codex-rs sends. null = field omitted (grok/openai-platform). */
    val summaryDelivery: String? = null,
) {
    // ── TOML overlays ────────────────────────────────────────────────────────
    // All five were file-level extensions on this type, spread across three files because
    // ResponsesRequestBuilder.kt used to sit at detekt's per-FILE TooManyFunctions ceiling. Kotlin
    // main sources carry no top-level functions, so they are members now — and since the receiver
    // was always a ResponsesQuirks, every call site is unchanged; consumers only drop the import.

    /**
     * Overlay the TOML `[providers.*.quirks]` primitives onto a provider's base profile so the parsed
     * table is REAL, not decorative (audit 2026-07-18: five of seven quirks were hard-coded and
     * ignored). Unset TOML fields keep the base value.
     */
    // NB: TOML's effort_ceiling is deliberately NOT an overlay input — the effort LADDER (CODEX/GROK)
    // already clamps the ceiling per provider; accepting a dead parameter here would just lie.
    public fun withToml(
        store: Boolean? = null,
        cacheKey: String? = null,
        summaryField: Boolean? = null,
        compactEffort: String? = null,
        toolChoice: Boolean? = null,
    ): ResponsesQuirks = copy(
        store = store ?: this.store,
        cacheKeyStrategy = when (cacheKey) {
            "session-id" -> CacheKeyStrategy.SESSION_ID
            "off" -> CacheKeyStrategy.OFF
            "first-message-hash" -> CacheKeyStrategy.FIRST_MESSAGE_HASH
            else -> this.cacheKeyStrategy
        },
        supportsSummary = summaryField ?: this.supportsSummary,
        compactEffortPin = compactEffort ?: this.compactEffortPin,
        emitToolChoice = toolChoice ?: this.emitToolChoice,
    )

    /** RC-5 overlay, chained after [withToml] (which sits at detekt's complexity ceiling). */
    public fun withReasoningCacheToml(reasoningCache: Boolean?): ResponsesQuirks =
        copy(reasoningCache = reasoningCache ?: this.reasoningCache)

    /** parallel_tool_calls overlay (2026-07-31), chained like [withReasoningCacheToml] for the same
     *  reason. NULLABLE — absent TOML keeps the provider's own default, so the overlay can never stomp
     *  it. (`summary_field` is non-nullable and DOES stomp `supportsSummary`, which is how that knob
     *  became unreachable from a provider default; not repeating it here.) */
    public fun withParallelToolCallsToml(parallelToolCalls: Boolean?): ResponsesQuirks =
        copy(liteParallelToolCalls = parallelToolCalls ?: this.liteParallelToolCalls)

    /** Overlay the head's TOML `[providers.*.quirks.tool_surface]` table — a DIRECT set, not the
     *  null-preserves-base merge [withReasoningCacheToml] uses: toolSurface's null means literally
     *  OFF (the field's own KDoc), and no provider's defaultQuirks() ever presets a non-null base to
     *  inherit from, so a direct set is both simpler and exactly as correct. Chained (not folded into
     *  [withToml]) because that function already sits at detekt's complexity ceiling. */
    public fun withToolSurfaceToml(policy: ToolDeferralPolicy?): ResponsesQuirks =
        copy(toolSurface = policy)

    /** ws-transport WS-3 overlay, NULLABLE like its siblings — absent TOML keeps the provider default
     *  (false). A non-nullable field would stomp the provider default; that is exactly how
     *  supportsSummary became an unreachable dead lever. */
    public fun withWebSocketToml(webSocket: Boolean?): ResponsesQuirks =
        copy(webSocket = webSocket ?: this.webSocket)
}

public enum class CacheKeyStrategy { FIRST_MESSAGE_HASH, SESSION_ID, OFF }

public enum class EffortLadder { CODEX, GROK }
