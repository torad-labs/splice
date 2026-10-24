// NEW: per-turn BuildOptions + lite-header construction. Split from
// ResponsesProvider.kt so the composer is not billed for the BuildOptions
// clot (concentration, 2026-08-19).
package splice.dialect.responses

import splice.core.model.ModelCatalog
import splice.core.parse.AnthropicTurnBody
import splice.core.reasoning.HISTORY_POSITION_PREFIX
import splice.core.reasoning.ReasoningReplay
import splice.core.util.LogSink
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.ReasoningCache
import splice.dialect.responses.reasoning.ReasoningCachePolicy
import splice.dialect.responses.reasoning.ReasoningCacheView
import splice.dialect.responses.reasoning.ReasoningHistoryAlias
import splice.dialect.responses.reasoning.ReasoningHistoryLookup
import splice.dialect.responses.reasoning.ReasoningHistoryUpdate
import splice.dialect.responses.reasoning.ReasoningLookup
import splice.dialect.responses.reasoning.ReasoningPositionIndex
import splice.dialect.responses.reasoning.ReasoningResultProofLookup
import splice.dialect.responses.reasoning.RequestEncryptedReasoning
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ModelIds
import splice.dialect.responses.request.ReasoningHandoff
import splice.dialect.responses.request.RequestedReasoning
import splice.dialect.responses.request.ResponsesStableIds
import splice.dialect.responses.tools.ToolSurfaceLatch

/** The gateway-held reasoning of one head: the cache, the key a conversation is filed under, and the opening
 *  hash that names it. It answers a build's reasoning lookups from ONE snapshot of the conversation. */
internal class ReasoningContinuity(
    val cache: ReasoningCache,
    val policy: ReasoningCachePolicy,
    private val ids: ResponsesStableIds,
) {
    /** The lookup for [body], keyed within [sessionId] as the capture keys it (ResponsesTurnSeams). Lazy, so a
     *  build with no tool_use blocks never touches the cache at all. */
    fun snapshotFor(body: AnthropicTurnBody, sessionId: String?): Lazy<ReasoningCacheView> = lazy {
        val legacy = policy.conversationKey(sessionId, ids.stablePromptCacheKey(body.typed))
        val scoped = sessionId?.takeIf { it.isNotEmpty() }?.let {
            policy.conversationKey(it, ids.reasoningStorageKey(body.typed))
        }
        val snapshot = cache.view(scoped ?: legacy, legacy)
        if (scoped == null) {
            snapshot.copy(
                rounds = snapshot.rounds.filterKeys { !it.startsWith(HISTORY_POSITION_PREFIX) },
                proofs = emptyMap(),
            )
        } else {
            snapshot
        }
    }

    fun alias(body: AnthropicTurnBody, sessionId: String?, update: ReasoningHistoryUpdate) {
        if (sessionId.isNullOrEmpty()) return
        val key = policy.conversationKey(sessionId, ids.reasoningStorageKey(body.typed)) ?: return
        cache.updateHistory(key, update)
    }
}

internal class ResponsesTurnOptions(
    private val reasoning: ReasoningSettings,
    private val quirks: ResponsesQuirks,
    private val catalog: ModelCatalog,
    private val log: LogSink,
    private val continuity: ReasoningContinuity,
    private val toolSurfaceLatch: ToolSurfaceLatch,
) {

    fun build(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuildOptions {
        // One reading for the whole build: the operator may PATCH these between turns, never inside one.
        val now = reasoning.now()
        val showOn = now.visible()
        // CMP-002: a transcript can carry many redacted_thinking blocks (appendRedactedThinking)
        // and many cached tool_use envelopes (appendToolUse's RC-3 lookup), both routed through
        // decodeReasoningEnvelope — logging every drop is transcript-length-proportional,
        // synchronous daemon.log I/O inside request building. Latched to ONE line per build (same
        // idiom as PassthroughStreamTranslator.unmappedIndexLogged); scoped to this call's local
        // var, not an instance field, since the provider itself is long-lived across many turns.
        var reasoningEnvelopeDropLogged = false
        val cached = cachedHistory(body, sessionId, now.replay)
        return BuildOptions(
            compact = compact,
            models = ModelIds(
                original = body.typed.model,
                upstream = catalog.stripSuffixes(body.typed.model),
            ),
            reasoning = RequestedReasoning(
                // Config-driven (TOML [daemon] / env / state); "none" suppresses when display is off.
                effort = now.effort,
                summary = now.summaryForRequest(),
                display = now.display,
            ),
            handoff = ReasoningHandoff(
                // Replay controls model history, independently of client presentation.
                replay = InjectPriorReasoning(now.replay),
                // Ask for the opaque encrypted handle whenever reasoning is visible OR the
                // reasoning cache needs it (RC-5: the cache can only hold what the server returns).
                // Not a function of `compact`: the request is built like a turn (the builder header).
                includeEncrypted = RequestEncryptedReasoning(showOn || now.replay || quirks.roundTrip.reasoningCache),
                decode = { data ->
                    ReasoningReplay.decodeReasoningEnvelope(data) { msg ->
                        if (!reasoningEnvelopeDropLogged) {
                            reasoningEnvelopeDropLogged = true
                            log(msg)
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
                lookup = ReasoningLookup { cached.value.rounds[it] },
                history = ReasoningHistoryLookup {
                    if (sessionId.isNullOrEmpty()) {
                        emptyMap()
                    } else {
                        ReasoningPositionIndex().positions(
                            cached.value.rounds,
                        )
                    }
                },
                historyAlias = ReasoningHistoryAlias { update -> continuity.alias(body, sessionId, update) },
                resultProofs = ReasoningResultProofLookup { cached.value.proofs },
            ),
            sessionId = sessionId,
            // The provider's capability latch, read at build time: false = a shape-400 already
            // closed it this daemon lifetime; build the full status-quo request instead.
            toolSurfaceOpen = toolSurfaceLatch.open,
        )
    }

    private fun cachedHistory(body: AnthropicTurnBody, sessionId: String?, replay: Boolean): Lazy<ReasoningCacheView> =
        if (quirks.roundTrip.reasoningCache || replay) {
            continuity.snapshotFor(body, sessionId)
        } else {
            lazy { ReasoningCacheView(emptyMap()) }
        }

    fun showOn(): Boolean = reasoning.now().visible()

    fun replayOn(): Boolean = reasoning.now().replay
}
