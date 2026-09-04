// NEW: streamTranslator / foldController / reanchorController for
// ResponsesProvider (concentration, 2026-08-19). Same-package; the provider
// keeps identity, buildTurn, and the WS/failure surfaces.
package splice.dialect.responses

import splice.core.reasoning.ReasoningReplay
import splice.core.turn.TurnMeta
import splice.spi.FoldController
import splice.spi.ReanchorController
import splice.spi.StreamTranslator
import splice.spi.TurnSignals

internal class ResponsesTurnSeams(private val deps: ResponsesTurnSeamsDeps) {
    // The controller is stateless — one cached instance serves every turn (a per-call
    // allocation here also ran per ROUND via the collectReasoningEnvelopes null-check).
    private val reanchorPolicy: ReanchorController by lazy {
        ResponsesReanchorController(decodeReasoningEnvelope = { ReasoningReplay.decodeReasoningEnvelope(it) })
    }

    fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        summaryOwner(meta).let { summaryOwner ->
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
                    emitEncryptedReasoning = EmitEncryptedReasoning(
                        deps.turnOptions.showOn() && deps.replayReasoning,
                    ),
                    encodeReasoningEnvelope = { ReasoningReplay.encodeReasoningEnvelope(it) },
                    clientGone = signals.clientGone,
                    watchdogFired = signals.watchdogFired,
                    streamIdleMsForMessage = deps.streamIdleMs,
                    upstreamTimeoutMsForMessage = deps.upstreamTimeoutMs,
                    dedupeRepeatedSummaryParts = deps.quirks.summaryDelivery != null,
                    // Conversation-lifetime state requires both identities. Missing either uses the
                    // turn's own state rather than risking cross-client suppression.
                    summaryPartsShared = meta.summaryParts,
                    summaryRoundScope = summaryOwner,
                    // Collect this round's encrypted reasoning envelopes whenever a continuation
                    // could consume them: fold replay (Success side) OR mid-stream re-anchor salvage
                    // (Failure side) — i.e. every non-compact responses turn since re-anchor landed
                    // (2026-07-24). Compact turns keep the collection off: their re-anchor (2026-09-02)
                    // can only ever be the verbatim restart, and their partial is usage-only, so an
                    // envelope collected on a compaction would be carried and never read.
                    collectReasoningEnvelopes = !meta.compact ||
                        deps.cachePolicy.reasoningCacheActive(deps.quirks, meta.compact),
                    onTurnReasoning = { ids, envs ->
                        if (deps.cachePolicy.reasoningCacheActive(deps.quirks, meta.compact)) {
                            deps.reasoningCache.put(meta.conversationKey, ids, envs)
                        }
                    },
                ),
            )
        }

    private fun summaryOwner(meta: TurnMeta): SummaryRoundOwner =
        deps.summaryParts.ownerForConversation(meta.sessionId, meta.conversationKey)
            ?: SummaryRoundScope(meta.summaryParts)

    // Non-null ONLY when folding is configured AND the turn's model is fold-eligible AND it is not a
    // compaction (a text summarizer requests no encrypted_content). Sol and every non-codex head get
    // null here → the gateway never buffers or loops → pure passthrough.
    fun foldController(meta: TurnMeta): FoldController? {
        val cfg = deps.foldConfig ?: return null
        if (meta.compact || meta.upstreamModel !in cfg.models) return null
        return ResponsesFoldController(
            cfg,
            decodeReasoningEnvelope = { ReasoningReplay.decodeReasoningEnvelope(it) },
        )
    }

    // Every responses turn is re-anchor eligible, compaction included (2026-09-02). Compaction was
    // excluded as "unary/buffered — the pre-handoff retry covers it", and the log says it did not:
    // G5 (ReissueRules) re-issues an SSE body that TEARS before any client frame, but a stream that
    // ENDS without response.completed — the WebSocket's status=1006, or an SSE body that closes
    // clean — reaches the translator, not G5, and the turn surfaced as overloaded_error to the
    // client (5 compactions in the daemon log, plus 3 server_is_overloaded events). Codex retries the
    // same event for its RemoteCompactionV2 (responses_retry.rs) and the user never sees it. A
    // compaction's partial is usage-only (ResponsesOutcomePayload), so the controller can only ever
    // answer with the verbatim whole-request restart — the shape a buffered turn needs — and the
    // deterministic verdicts (cyber_policy, refusals, content filter) carry no partial and are never
    // re-POSTed. NB: fold-eligible turns get re-anchor via FoldRunner's trigger-B, not
    // ReanchorRunner (driveOneTurn routes fold first).
    fun reanchorController(): ReanchorController = reanchorPolicy
}
