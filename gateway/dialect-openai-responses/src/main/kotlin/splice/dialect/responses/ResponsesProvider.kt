// NEW: the shared base for every openai-responses Provider (codex / grok / openai-platform),
// extracted 2026-07-18 (craft review). buildTurn + streamTranslator were byte-identical across the
// three providers — the exact "port the neighbor, copies drift (v29 lesson)" failure the codebase
// legislates against, moved one layer up from the dialect. Subclasses now supply ONLY what genuinely
// differs: their quirk profile, extraHeaders, and (grok) a per-turn header hook. The reasoning-policy
// wiring — include-encrypted when shown, input-replay only on operator opt-in, emit redacted_thinking
// on the stream — lives here ONCE.
package splice.dialect.responses

import splice.core.parse.AnthropicTurnBody
import splice.core.reasoning.decodeReasoningEnvelope
import splice.core.reasoning.encodeReasoningEnvelope
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.spi.BuiltTurn
import splice.spi.FoldController
import splice.spi.Provider
import splice.spi.ProviderIdentity
import splice.spi.ProviderTuning
import splice.spi.ReanchorController
import splice.spi.StreamTranslator
import splice.spi.TurnSignals
import splice.spi.UpstreamClient

public abstract class ResponsesProvider(
    tuning: ProviderTuning,
    final override val showReasoning: ReasoningDisplay,
    final override val replayReasoning: Boolean,
    private val configEffort: String?,
    private val configSummary: String?,
    protected val quirks: ResponsesQuirks,
    // Reasoning-continuation folding (codex 518n-2). null = the feature is off for this provider —
    // grok/openai-platform pass nothing → pure passthrough. Only CodexProvider wires a real config.
    private val foldConfig: FoldConfig? = null,
) : Provider, ProviderIdentity by tuning {

    final override val upstreamUrl: String = "${tuning.baseUrl}/responses"
    private val builder = ResponsesRequestBuilder(quirks)

    /** Per-turn upstream headers beyond the shared Accept set (grok's x-grok-conv-id). Empty by
     *  default — a header that depends on the turn/session rides HERE, never on shared state. */
    protected open fun perTurnHeaders(sessionId: String?): Map<String, String> = emptyMap()

    final override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        val upstreamModel = catalog.stripSuffixes(body.typed.model)
        val showOn = !showReasoning.isOff
        val built = builder.build(
            body.typed,
            body.raw,
            BuildOptions(
                compact = compact,
                originalModel = body.typed.model,
                upstreamModel = upstreamModel,
                // Config-driven (TOML [daemon] / env / state); "none" suppresses when display is off.
                configEffort = configEffort,
                configSummary = if (showOn) configSummary else "none",
                showReasoning = showReasoning,
                // LEGACY client-round-trip replay (redacted_thinking through Claude Code) —
                // operator opt-in only; superseded by the gateway-held reasoning cache below.
                replayReasoning = InjectPriorReasoning(replayReasoning),
                // Ask for the opaque encrypted handle whenever reasoning is visible OR the
                // reasoning cache needs it (RC-5: the cache can only hold what the server returns).
                includeEncryptedReasoning = RequestEncryptedReasoning(
                    (showOn && !compact) || reasoningCacheActive(quirks, compact),
                ),
                sessionId = sessionId,
                decodeReasoningEnvelope = { decodeReasoningEnvelope(it) },
                // RC-5: gateway-held reasoning continuity — the turn that emitted these tool ids
                // left its plan in the cache; reinject it so the model resumes instead of
                // re-deriving (codex parity; repeated-tool-call amnesia otherwise). Scoped to
                // THIS conversation (same first-message hash the builder stamps on TurnMeta).
                reasoningLookup = { id ->
                    if (reasoningCacheActive(quirks, compact)) {
                        reasoningCache.lookup(stablePromptCacheKey(body.typed), id)
                    } else {
                        null
                    }
                },
            ),
        )
        return BuiltTurn(built.req, built.meta, perTurnHeaders(sessionId) + liteHeaders(built.meta))
    }

    /** codex-rs sends this marker header for responses-lite (5.6-family) turns; compact turns keep
     *  the normal shape so the header stays off there too (mirrors the builder's lite gate). */
    private fun liteHeaders(meta: TurnMeta): Map<String, String> =
        if (!meta.compact && quirks.responsesLiteModelRegex?.containsMatchIn(meta.upstreamModel) == true) {
            mapOf("x-openai-internal-codex-responses-lite" to "true")
        } else {
            emptyMap()
        }

    final override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        ResponsesStreamTranslator(
            StreamTurnContext(
                compact = meta.compact,
                // STREAM-side emission of redacted_thinking wire blocks (so Claude Code stores the
                // handle for the NEXT turn's replay). COUPLED to replayReasoning (2026-07-20): a
                // handle the gateway will never inject back is pure cost — each redacted_thinking
                // block is a content_block_start with NO thinking_delta, which Claude Code renders as
                // a permanent empty "✳ Thinking…" spinner; a deep turn emits dozens (the "walls of
                // Thinking" report). With replay OFF (default) the whole transcript-replay loop is off
                // end-to-end: no empty spinners, and reasoning is re-derived fresh (deeper) each turn.
                // The live summary thinking blocks (reasoning_summary_text deltas) are a SEPARATE path
                // and still display. Fold's own intra-turn reasoning replay is independent of this.
                emitEncryptedReasoning = EmitEncryptedReasoning(showOn() && replayReasoning),
                encodeReasoningEnvelope = { encodeReasoningEnvelope(it) },
                clientGone = signals.clientGone,
                watchdogFired = signals.watchdogFired,
                streamIdleMsForMessage = watchdog.streamIdle.inWholeMilliseconds,
                upstreamTimeoutMsForMessage = watchdog.totalCap.inWholeMilliseconds,
                dedupeRepeatedSummaryParts = quirks.summaryDelivery != null,
                // Collect this round's encrypted reasoning envelopes whenever a continuation
                // could consume them: fold replay (Success side) OR mid-stream re-anchor salvage
                // (Failure side) — i.e. every non-compact responses turn since re-anchor landed
                // (2026-07-24). Compact turns keep the collection off.
                collectReasoningEnvelopes = foldController(meta) != null || reanchorController(meta) != null ||
                    reasoningCacheActive(quirks, meta.compact),
                onTurnReasoning = { ids, envs ->
                    if (reasoningCacheActive(quirks, meta.compact)) {
                        reasoningCache.put(meta.conversationKey, ids, envs)
                    }
                },
            ),
        )

    // Non-null ONLY when folding is configured AND the turn's model is fold-eligible AND it is not a
    // compaction (a text summarizer requests no encrypted_content). Sol and every non-codex head get
    // null here → the gateway never buffers or loops → pure passthrough.
    final override fun foldController(meta: TurnMeta): FoldController? {
        val cfg = foldConfig ?: return null
        if (meta.compact || meta.upstreamModel !in cfg.models) return null
        return ResponsesFoldController(cfg, decodeReasoningEnvelope = { decodeReasoningEnvelope(it) })
    }

    // RC-2/RC-4: gateway-held reasoning continuity for tool round-trips (codex parity). One
    // cache per provider instance; capture and lookup wire in via buildTurn/streamTranslator.
    private val reasoningCache: ReasoningCache = ReasoningCache()

    /** RC-4: a 400 rejecting stale encrypted reasoning strips the injected items and retries
     *  once (NEVER-BELOW-STATUS-QUO law); every other failure keeps the plain retry plan.
     *  Keyed off the SAME classifier as the retry plan's GIVE_UP (review 2026-07-24: a narrower
     *  literal match here let any upstream wording drift skip the recovery entirely). */
    final override fun amendBodyOnFailure(status: Int, responseText: String, bodyJson: String): String? {
        if (!UpstreamClient.isEncryptedContentError(status, responseText)) return null
        return stripStaleReasoning(bodyJson, reasoningCache)
    }

    // The controller is stateless — one cached instance serves every turn (a per-call
    // allocation here also ran per ROUND via the collectReasoningEnvelopes null-check).
    private val reanchorPolicy: ReanchorController by lazy {
        ResponsesReanchorController(decodeReasoningEnvelope = { decodeReasoningEnvelope(it) })
    }

    // Every non-compact responses turn is re-anchor eligible (compaction is unary/buffered — the
    // pre-handoff retry covers it). NB: fold-eligible turns get re-anchor via FoldRunner's
    // trigger-B, not ReanchorRunner (driveOneTurn routes fold first).
    final override fun reanchorController(meta: TurnMeta): ReanchorController? =
        if (meta.compact) null else reanchorPolicy

    private fun showOn(): Boolean = !showReasoning.isOff
}
