// NEW: per-turn BuildOptions + lite-header construction. Split from
// ResponsesProvider.kt so the composer is not billed for the BuildOptions
// clot (concentration, 2026-08-19).
package splice.dialect.responses

import splice.core.parse.AnthropicTurnBody
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.TurnMeta

// TurnOptionsDeps lives in TurnOptionsDeps.kt (concentration, 2026-08-19).

internal class ResponsesTurnOptions(
    private val deps: TurnOptionsDeps,
) {

    fun build(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuildOptions {
        val showOn = showOn()
        // CMP-002: a transcript can carry many redacted_thinking blocks (appendRedactedThinking)
        // and many cached tool_use envelopes (appendToolUse's RC-3 lookup), both routed through
        // decodeReasoningEnvelope — logging every drop is transcript-length-proportional,
        // synchronous daemon.log I/O inside request building. Latched to ONE line per build (same
        // idiom as PassthroughStreamTranslator.unmappedIndexLogged); scoped to this call's local
        // var, not an instance field, since the provider itself is long-lived across many turns.
        var reasoningEnvelopeDropLogged = false
        return BuildOptions(
            compact = compact,
            originalModel = body.typed.model,
            upstreamModel = deps.catalog.stripSuffixes(body.typed.model),
            // Config-driven (TOML [daemon] / env / state); "none" suppresses when display is off.
            configEffort = deps.configEffort,
            configSummary = if (showOn) deps.configSummary else "none",
            showReasoning = deps.showReasoning,
            // LEGACY client-round-trip replay (redacted_thinking through Claude Code) —
            // operator opt-in only; superseded by the gateway-held reasoning cache below.
            replayReasoning = InjectPriorReasoning(deps.replayReasoning),
            // Ask for the opaque encrypted handle whenever reasoning is visible OR the
            // reasoning cache needs it (RC-5: the cache can only hold what the server returns).
            // Not a function of `compact`: the request is built like a turn (the builder header).
            includeEncryptedReasoning = RequestEncryptedReasoning(showOn || deps.quirks.reasoningCache),
            sessionId = sessionId,
            decodeReasoningEnvelope = { data ->
                ReasoningReplay.decodeReasoningEnvelope(data) { msg ->
                    if (!reasoningEnvelopeDropLogged) {
                        reasoningEnvelopeDropLogged = true
                        deps.log(msg)
                    }
                }
            },
            // RC-5: gateway-held reasoning continuity — the turn that emitted these tool ids
            // left its plan in the cache; reinject it so the model resumes instead of
            // re-deriving (codex parity; repeated-tool-call amnesia otherwise). Scoped to
            // THIS conversation (same first-message hash the builder stamps on TurnMeta).
            // ONE atomic snapshot per build (review of #71 round 2): per-block lookups could
            // tear across a concurrent eviction (rounds 1..k injected, k+1.. missing), re-ran
            // the first-message SHA-256 per block, and re-touched the conversation per block.
            // Lazy so a build with no tool_use blocks never touches the cache at all. Wired on a
            // compaction too: the session's turns carry these reasoning items in their input, so
            // a compaction built without them shares no prefix with them (2026-09-05).
            reasoningLookup = if (!deps.quirks.reasoningCache) {
                { null }
            } else {
                val snapshot = lazy { deps.reasoningCache.snapshot(deps.ids.stablePromptCacheKey(body.typed)) }
                ({ id -> snapshot.value[id] })
            },
            // The provider's capability latch, read at build time: false = a shape-400 already
            // closed it this daemon lifetime; build the full status-quo request instead.
            toolSurfaceOpen = deps.toolSurfaceLatch.open,
        )
    }

    /** codex-rs sends this marker header for responses-lite (5.6-family) turns — every turn on
     *  such a model, compaction included (mirrors the builder's lite gate). */
    fun liteHeaders(meta: TurnMeta): Map<String, String> =
        if (deps.quirks.responsesLiteModelRegex?.containsMatchIn(meta.upstreamModel) == true) {
            mapOf("x-openai-internal-codex-responses-lite" to "true")
        } else {
            emptyMap()
        }

    fun showOn(): Boolean = !deps.showReasoning.isOff
}
