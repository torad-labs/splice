// PORT-OF: splice/gateway/head/TurnDriver.kt (RoundUsage, and FoldRunner's private nested
// RoundCursor) @ 86f1411 — invariants unchanged: the two value types the round loop passes around —
// the cumulative-usage accumulator and the loop cursor. RoundCursor is promoted from a private
// nested class to internal top-level so it can serve as the return/parameter type between
// FoldRunner and FoldRounds once the fold-continuation check lives in its own file.
package splice.head.round

import kotlinx.serialization.json.JsonObject
import splice.core.turn.AbsorbedRounds
import splice.core.turn.Usage
import splice.core.turn.UsageField

/** One position in the round loop: the body to POST plus the two round counters. Serves BOTH
 *  directions — passed INTO FoldRounds.nextRoundBody as the current cursor and returned as the
 *  next one (detekt 2026-07-24: the 8-arg form tripped LongParameterList). */
internal data class RoundCursor(val body: JsonObject, val roundIndex: Int, val searchIndex: Int)

/** The round-usage law, ONE implementation for both runners (2026-07-20, unified in the
 *  code-review 2026-07-24): each continuation re-sends the ENTIRE conversation, so input/cached
 *  are CUMULATIVE — round N already includes round N-1's; summing them (the old `Usage.plus`)
 *  inflated the client-visible prompt up to ~Nx, firing the context bar / autocompact early.
 *  Only output/reasoning genuinely accrue per round. */
internal data class RoundUsage(
    val lastInput: Long = 0,
    val lastCached: Long = 0,
    val outSum: Long = 0,
    val reasoningSum: Long = 0,
    /** V4-85: the cache-WRITE half of [lastInput], under the same cumulative law — a continuation
     *  re-sends the whole conversation, so round N's cache_creation already contains round N-1's. */
    val lastCacheWrite: Long = 0,
    val localStep: Boolean = false,
    val codeModeDiverged: Boolean = false,
    val recordedOutputSum: Long = 0,
    /** A code-mode step's client context rides to the terminal; the client reads it only when the
     *  turn's final round measured no input ([splice.core.turn.Usage.clientContext]). */
    val clientContext: Usage? = null,
    /** Each round this law superseded, billed as a request of its own ([AbsorbedRounds]): the
     *  cumulative law keeps the context, and this keeps the bill. */
    val absorbed: AbsorbedRounds = AbsorbedRounds(),
    /** Source rounds the turn cut while they streamed ([splice.core.turn.Usage.cutRounds]); they accrue. */
    val cutRounds: Long = 0,
    val reported: Set<UsageField> = emptySet(),
) {
    fun plusRound(u: Usage) = RoundUsage(
        lastInput = if (UsageField.INPUT in u.reported) u.inputTokens else lastInput,
        lastCached = if (UsageField.CACHED in u.reported) u.cachedTokens else lastCached,
        outSum = outSum + u.outputTokens,
        reasoningSum = reasoningSum + u.reasoningTokens,
        lastCacheWrite = if (UsageField.CACHE_WRITE in u.reported) u.cacheWriteTokens else lastCacheWrite,
        localStep = localStep || u.localStep,
        codeModeDiverged = codeModeDiverged || u.codeModeDiverged,
        recordedOutputSum = recordedOutputSum + u.recordedOutputTokens,
        clientContext = u.clientContext ?: clientContext,
        absorbed = absorbed + toUsage().finalRound + u.absorbed,
        cutRounds = cutRounds + u.cutRounds,
        reported = reported + u.reported,
    )

    /** Fold a failed round under the same cumulative law. Unreported buckets preserve their
     *  last observation; a reported zero replaces it just as a positive observation does. */
    fun plusTerminal(u: Usage) = RoundUsage(
        lastInput = if (UsageField.INPUT in u.reported) u.inputTokens else lastInput,
        lastCached = if (UsageField.CACHED in u.reported) u.cachedTokens else lastCached,
        outSum = outSum + u.outputTokens,
        reasoningSum = reasoningSum + u.reasoningTokens,
        lastCacheWrite = if (UsageField.CACHE_WRITE in u.reported) u.cacheWriteTokens else lastCacheWrite,
        localStep = localStep || u.localStep,
        codeModeDiverged = codeModeDiverged || u.codeModeDiverged,
        recordedOutputSum = recordedOutputSum + u.recordedOutputTokens,
        clientContext = u.clientContext ?: clientContext,
        absorbed = absorbed +
            (if (UsageField.INPUT in u.reported) toUsage().finalRound else AbsorbedRounds()) + u.absorbed,
        cutRounds = cutRounds + u.cutRounds,
        reported = reported + u.reported,
    )

    fun toUsage() = Usage(
        inputTokens = lastInput,
        outputTokens = outSum,
        cachedTokens = lastCached,
        reasoningTokens = reasoningSum,
        cacheWriteTokens = lastCacheWrite,
        localStep = localStep,
        codeModeDiverged = codeModeDiverged,
        recordedOutputTokens = recordedOutputSum,
        clientContext = clientContext,
        absorbed = absorbed,
        cutRounds = cutRounds,
        reported = reported,
    )
}
