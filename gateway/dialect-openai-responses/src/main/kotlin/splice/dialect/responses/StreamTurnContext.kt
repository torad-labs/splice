// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: a pure per-turn input
// DTO, assembled once per round and handed to the translator; every field's meaning and default is
// byte-identical to the pre-split declaration.
package splice.dialect.responses

import splice.core.turn.SharedSummaryParts
import splice.spi.ClientGone
import splice.spi.WatchdogProbe

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
    /** Turn-scoped dedup state shared across this turn's continuation rounds (fresh translator
     *  per round; without it, a section re-titled by a continuation round passes each round's
     *  per-instance dedup and lands as a duplicate — the 2026-07-26 mirror duplication).
     *
     *  REQUIRED, no default (2026-07-27 review): it used to be `SharedSummaryParts? = null` for
     *  test convenience, and a null here silently restores exactly the per-round private state the
     *  turn-scoping exists to remove. Production passes `meta.summaryParts`; a test that wants
     *  round-private state now has to say so. */
    val summaryPartsShared: SharedSummaryParts,
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
