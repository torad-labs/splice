// NEW: streamTranslator / foldPolicy / reanchorPolicy for
// ResponsesProvider (concentration, 2026-08-19). Same-package; the provider
// keeps identity, buildTurn, and the WS/failure surfaces.
package splice.dialect.responses

import splice.core.reasoning.ReasoningReplay
import splice.core.turn.TurnMeta
import splice.core.util.LogSink
import splice.dialect.responses.reasoning.EmitEncryptedReasoning
import splice.dialect.responses.reasoning.ReasoningItemOrder
import splice.dialect.responses.reasoning.ReasoningPositionIndex
import splice.dialect.responses.reasoning.ResponsesReanchorPolicy
import splice.dialect.responses.reasoning.TurnReasoningSink
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.stream.ResponsesFoldPolicy
import splice.dialect.responses.stream.ResponsesStreamTranslator
import splice.upstream.FoldPolicy
import splice.upstream.ReanchorPolicy
import splice.upstream.StreamTranslator
import splice.upstream.TurnSignals

internal class ResponsesTurnSeams(private val deps: ResponsesTurnSeamsDeps) {
    // The controller is stateless — one cached instance serves every turn (a per-call
    // allocation here also ran per ROUND via the collectReasoningEnvelopes null-check).
    // One per wire shape. Each provider owns the phase carried by its continuation partial prose.
    private val defaultReanchorPolicy: ReanchorPolicy by lazy {
        ResponsesReanchorPolicy(
            decodeReasoningEnvelope = { ReasoningReplay.decodeReasoningEnvelope(it) },
            prosePhase = deps.quirks.roundTrip.reanchorProsePhase,
            continueReasoningOnly = deps.quirks.roundTrip.continueReasoningOnly,
        )
    }
    private val liteReanchorPolicy: ReanchorPolicy by lazy {
        ResponsesReanchorPolicy(
            decodeReasoningEnvelope = { ReasoningReplay.decodeReasoningEnvelope(it) },
            prosePhase = AssistantPhase.COMMENTARY,
            continueReasoningOnly = deps.quirks.roundTrip.continueReasoningOnly,
        )
    }
    private val liteShape = ResponsesLiteShape(deps.quirks)

    fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        summaryOwner(meta).let { summaryOwner ->
            ResponsesStreamTranslator(
                StreamTurnContext(
                    compact = meta.compact,
                    // Storage and readable presentation are independent. The legacy flag stays in the
                    // context for source compatibility; the replay fold never writes opaque wire blocks.
                    emitEncryptedReasoning = EmitEncryptedReasoning(false),
                    encodeReasoningEnvelope = { ReasoningReplay.encodeReasoningEnvelope(it) },
                    signals = signals,
                    caps = deps.caps,
                    summary = SummaryHandling(
                        dedupeRepeatedParts = deps.quirks.reasoning.summaryDelivery != null,
                        // Conversation-lifetime state requires both identities. Missing either uses the
                        // turn's own state rather than risking cross-client suppression.
                        partsShared = meta.scope.summaryParts,
                        roundScope = summaryOwner,
                    ),
                    reasoningCapture = ReasoningCapture(
                        codeMode = meta.tools.codeMode,
                        // Collect this round's encrypted reasoning envelopes whenever a continuation
                        // could consume them: fold replay (Success side) OR mid-stream re-anchor salvage
                        // (Failure side) — i.e. every non-compact responses turn since re-anchor landed
                        // (2026-07-24). Compact turns keep the collection off: their re-anchor (2026-09-02)
                        // can only ever be the verbatim restart, and their partial is usage-only, so an
                        // envelope collected on a compaction would be carried and never read.
                        collectEnvelopes = !meta.compact ||
                            deps.continuity.policy.reasoningCacheActive(deps.quirks, meta.compact),
                        onTurn = reasoningSink(meta),
                        history = meta.scope.reasoningHistory.takeIf {
                            !meta.scope.sessionId.isNullOrEmpty() && meta.scope.reasoningStorageKey != null
                        },
                    ),
                ),
                deps.services.toolNames,
                LogSink { line -> deps.services.log("[${deps.quirks.providerTag}] $line") },
                outputToolName = meta.tools.outputToolName,
                reading = deps.services.reading,
            )
        }

    /** The turn's writes to the reasoning cache, keyed within its session as the lookup keys them
     *  (ResponsesTurnOptions): a tool round's reasoning in, and, on a compaction's Success, the
     *  conversation out (V4-334). Both only where the quirk runs the cache. */
    private fun reasoningSink(meta: TurnMeta): TurnReasoningSink {
        val opening = if (meta.scope.sessionId.isNullOrEmpty()) {
            meta.scope.reasoningStorageKey
        } else {
            meta.scope.conversationKey
        }
        val conversation = deps.continuity.policy.conversationKey(meta.scope.sessionId, opening)
        val positioned = meta.scope.sessionId?.takeIf { it.isNotEmpty() }?.let {
            deps.continuity.policy.conversationKey(it, meta.scope.reasoningStorageKey)
        }
        return object : TurnReasoningSink {
            override fun invoke(toolIds: List<String>, envelopes: List<String>) {
                if (conversation != null && retainsToolReasoning(meta)) {
                    deps.continuity.cache.put(conversation, toolIds, envelopes)
                }
            }

            override fun compacted() {
                deps.continuity.cache.dropConversation(conversation)
                deps.continuity.cache.dropConversation(positioned)
                deps.continuity.phases.dropConversation(positioned)
            }

            override fun assistantPhases(records: Map<String, String>) {
                if (positioned == null || meta.compact) return
                records.forEach { (index, record) ->
                    deps.continuity.phases.put(positioned, listOf(index), listOf(record))
                }
            }

            override fun positioned(position: String, envelope: String, order: ReasoningItemOrder?) {
                if (positioned == null || meta.compact) return
                val storageEnabled = deps.quirks.roundTrip.reasoningCache || deps.turnOptions.replayOn()
                if (!storageEnabled) return
                // Private cache index, not a synthetic upstream call id. The envelope digest makes retries
                // idempotent while distinct reasoning-only rounds may share the same visible boundary.
                val index = ReasoningPositionIndex().index(position, envelope, order)
                deps.continuity.cache.put(positioned, listOf(index), listOf(envelope))
            }
        }
    }

    private fun retainsToolReasoning(meta: TurnMeta): Boolean {
        if (meta.compact) return false
        if (deps.quirks.roundTrip.reasoningCache) return true
        return meta.scope.sessionId.isNullOrEmpty() && deps.turnOptions.replayOn()
    }

    private fun summaryOwner(meta: TurnMeta): SummaryRoundOwner =
        deps.summaryParts.ownerForConversation(meta.scope.sessionId, meta.scope.conversationKey)
            ?: SummaryRoundScope(meta.scope.summaryParts)

    // Non-null ONLY when the provider declared a fold AND the turn's model is one it cuts AND it is not a
    // compaction (a text summarizer requests no encrypted_content). Sol and every non-codex head get
    // null here → the gateway never buffers or loops → pure passthrough.
    fun foldPolicy(meta: TurnMeta): FoldPolicy? {
        val cfg = deps.carry.fold ?: return null
        if (meta.compact || meta.route.upstreamModel !in cfg.models) return null
        return ResponsesFoldPolicy(
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
    // deterministic verdicts (cyber_policy, refusals, content filter) are never re-POSTed: RetryEngine
    // ends each at once, whatever salvage its round carries (failure-handling slice 4). NB: fold-eligible
    // turns get re-anchor via FoldRunner's trigger-B, not
    // ReanchorRunner (driveOneTurn routes fold first).
    fun reanchorPolicy(meta: TurnMeta): ReanchorPolicy =
        if (liteShape.isLiteModel(meta.route.upstreamModel)) liteReanchorPolicy else defaultReanchorPolicy
}
