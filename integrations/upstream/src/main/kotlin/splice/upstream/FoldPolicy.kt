// NEW: reasoning-continuation folding SPI (the codex 518n-2 "dumbing down" fix). The generic head
// (:daemon-head) drives THIS; the continuation-request construction lives in the openai-responses
// dialect. Keeping the contract here (not in the dialect) is why :daemon-head can fold a truncated
// round without ever importing a concrete dialect — the module law again. Failure-handling slice 4:
// only the builder is here. Whether a round was cut is its provider's reading (Replies.completed,
// codex's in CodexReplies), and whether it is continued is RetryEngine's, on the turn's one ledger.
package splice.upstream

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome

/** Per-turn continuation builder. A provider returns one from [Provider.foldPolicy] when the
 *  turn's model is one whose rounds it may cut; null means pure passthrough (no buffering, no loop —
 *  byte-for-byte the pre-fold behaviour). Asked by the gateway for a completed round its provider
 *  read as cut, once the engine continues it. */
public fun interface FoldPolicy {
    /**
     * The NEXT upstream request body for a cut round (this round's input + replayed reasoning + a
     * continuation marker). Null when the round left no encrypted reasoning to replay: the gateway
     * then flushes the buffered output and emits the single honest terminal.
     */
    public fun continuation(round: FoldRound): JsonObject?
}

/** One completed fold round: the request that produced it and its outcome (usage + reasoning envelopes). */
public data class FoldRound(
    val requestBody: JsonObject,
    val outcome: TurnOutcome.Success,
)

/** Mid-stream re-anchoring policy (eli design 2026-07-24): when a round FAILS after frames were
 *  already forwarded, the provider may return the continuation request that resumes the turn from
 *  its accumulated partial output. Null = the round cannot be carried on from what arrived (a poison tool tear, a
 *  committed tool call, or prose a provider cannot continue from), and the turn ends with the honest error. Whether a
 *  failure is carried on at all, and how many times, is RetryEngine's answer on the turn's one ledger: this answer is
 *  what its TurnFacts.canContinue reads (failure-handling slice 4). Distinct from [FoldPolicy], which continues
 *  SUCCESSFUL rounds whose reasoning was truncated. */
public fun interface ReanchorPolicy {
    public fun continuationForFailure(round: ReanchorRound): JsonObject?

    /** Whether a failure at this point of the turn could still be carried on, asked of a round still streaming: the
     *  live listing's "will it resume". A dialect that has a rule overrides it with the same conditions
     *  [continuationForFailure] refuses on; the default is no rule. */
    public fun wouldContinue(live: LiveRound): Boolean = false

    /** The request that carries on from a round that completed with nothing for the client, sent in place of the
     *  identical re-send; null keeps that re-send. A dialect whose vendor ends rounds after reasoning alone builds
     *  it from the round's own reasoning; the default is no such rule. */
    public fun continuationForEmpty(requestBody: JsonObject, empty: TurnOutcome.Success): JsonObject? = null
}

/** Where a streaming turn stands, as far as a re-anchor rule can tell: how many continuations it has spent, which
 *  the rule reads for no budget (the engine counts them on the turn's ledger), and what the client already holds. */
public data class LiveRound(val attempt: Int, val toolOpened: Boolean, val textWritten: Boolean)

/** One failed round: the request that produced it, its failure (carrying the partial), and how
 *  many mid-stream continuations this turn already spent (0-based), which no rule reads as a budget. */
public data class ReanchorRound(
    val requestBody: JsonObject,
    val failure: TurnOutcome.Failure,
    val attempt: Int,
)
