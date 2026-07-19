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
import splice.spi.StreamTranslator
import splice.spi.TurnSignals

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
                // INPUT injection of prior opaque encrypted reasoning items — operator opt-in ONLY (default OFF; it
                // thins fresh reasoning ~4x). Never derived from showReasoning.
                replayReasoning = InjectPriorReasoning(replayReasoning),
                // Ask for the opaque encrypted handle whenever reasoning is visible.
                includeEncryptedReasoning = RequestEncryptedReasoning(showOn && !compact),
                sessionId = sessionId,
                decodeReasoningEnvelope = { decodeReasoningEnvelope(it) },
            ),
        )
        return BuiltTurn(built.req, built.meta, perTurnHeaders(sessionId))
    }

    final override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        ResponsesStreamTranslator(
            StreamTurnContext(
                compact = meta.compact,
                // STREAM-side emission of redacted_thinking wire blocks (so Claude Code stores the
                // handle). Distinct from BuildOptions.replayReasoning (input injection).
                emitEncryptedReasoning = EmitEncryptedReasoning(showOn()),
                encodeReasoningEnvelope = { encodeReasoningEnvelope(it) },
                clientGone = signals.clientGone,
                watchdogFired = signals.watchdogFired,
                streamIdleMsForMessage = watchdog.streamIdle.inWholeMilliseconds,
                upstreamTimeoutMsForMessage = watchdog.totalCap.inWholeMilliseconds,
                dedupeRepeatedSummaryParts = quirks.summaryDelivery != null,
                // Collect this round's encrypted reasoning envelopes ONLY when folding is active for
                // the turn — off keeps the reducer byte-identical for sol / every non-fold turn.
                collectReasoningEnvelopes = foldController(meta) != null,
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

    private fun showOn(): Boolean = !showReasoning.isOff
}
