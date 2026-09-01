// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — a pure per-turn input DTO, assembled once per
// round and handed to the translator. SummaryRoundOwner was added for conversation-lifetime leasing;
// the remaining fields keep the pre-split meanings.
package splice.dialect.responses

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.turn.SharedSummaryParts
import splice.spi.ClientGone
import splice.spi.WatchdogProbe

/** Work executed under one summary round's lease and coroutine mutex. */
public fun interface SummaryRoundTask<T> {
    public suspend operator fun invoke(parts: SharedSummaryParts): T
}

/** Supplies one shared summary state while owning a complete translator round. */
public interface SummaryRoundOwner {
    public suspend fun <T> withRound(task: SummaryRoundTask<T>): T
}

/** Turn-private whole-round ownership. Conversation entries implement the same contract through
 *  their registry so an entry cannot expire while a round is waiting or running. */
public class SummaryRoundScope(public val parts: SharedSummaryParts) : SummaryRoundOwner {
    private val mutex = Mutex()

    override suspend fun <T> withRound(task: SummaryRoundTask<T>): T = mutex.withLock {
        parts.beginRound()
        try {
            task(parts)
        } finally {
            parts.endRound()
        }
    }
}

/** Per-turn inputs the machine needs beyond the event flow. */
public data class StreamTurnContext(
    val compact: Boolean,
    /** EMIT redacted_thinking wire blocks when encrypted_content arrives (so Claude Code can
     *  store the opaque handle). NB: this is the STREAM-side emission flag — distinct from and
     *  opposite to BuildOptions.replayReasoning, which INJECTS prior reasoning into the request
     *  input. Same concept split into two honestly-named flags (craft review). */
    val emitEncryptedReasoning: EmitEncryptedReasoning,
    /** Encodes a reasoning item into the redacted_thinking envelope (gateway supplies; the
     *  envelope codec is splice-reasoning v1 and lands with P3-MIR). */
    val encodeReasoningEnvelope: ReasoningEnvelopeEncoder,
    /** True when the downstream client connection is already gone (client-abort detection). */
    val clientGone: ClientGone,
    /** The watchdog's typed sentinel, read AFTER the loop ends. */
    val watchdogFired: WatchdogProbe,
    val streamIdleMsForMessage: Long,
    val upstreamTimeoutMsForMessage: Long,
    /** sequential_cutoff delivery restates earlier summary parts on every new reasoning item
     *  (probed 2026-07-19: part(1,0) byte-identical to part(0,0)); codex-rs dedups client-side.
     *  Gated to the delivery quirk so genuine token-granular streams are never touched. */
    val dedupeRepeatedSummaryParts: Boolean = false,
    /** Turn-scoped fallback state shared across continuation rounds. Production uses it when the
     *  client has no complete session+conversation identity; tests also use it with the default
     *  owner below. REQUIRED: null used to silently restore round-private dedup. */
    val summaryPartsShared: SharedSummaryParts,
    /** Whole-round owner. A keyed production owner leases and supplies its conversation entry's
     *  state; the default owns [summaryPartsShared] directly for unkeyed turns and tests. */
    val summaryRoundScope: SummaryRoundOwner = SummaryRoundScope(summaryPartsShared),
    /** Encode this round's encrypted reasoning items into splice-reasoning envelopes, riding the
     *  Success outcome (fold replay) AND the Failure partial (re-anchor salvage, 2026-07-24).
     *  True for every fold- or re-anchor-eligible turn; off (compact) keeps the reducer
     *  collection-free. */
    val collectReasoningEnvelopes: Boolean = false,
    /** Reasoning-cache capture (RC-1, 2026-07-24): called once at a successful tool-use terminal
     *  with the round's REAL upstream function_call ids and its ordered reasoning envelopes —
     *  codex-rs parity (store:false full replay, client.rs:888/:915) held GATEWAY-side so the
     *  next tool-result request can reinject the model's plan in-position. Synthetic tool ids
     *  never key the cache (toolu_synth_* repeats across turns — cross-turn bleed). Default no-op
     *  keeps the reducer byte-identical when the provider wires no cache. */
    val onTurnReasoning: TurnReasoningSink = TurnReasoningSink { _, _ -> },
)
