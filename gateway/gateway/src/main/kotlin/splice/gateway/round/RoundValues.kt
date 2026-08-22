// PORT-OF: splice/gateway/head/TurnDriver.kt (RoundUsage, and FoldRunner's private nested
// RoundCursor) @ 86f1411 — invariants unchanged: the two value types the round loop passes around —
// the cumulative-usage accumulator and the loop cursor. RoundCursor is promoted from a private
// nested class to internal top-level so it can serve as the return/parameter type between
// FoldRunner and FoldRounds once the fold-continuation check lives in its own file.
package splice.gateway.round

import kotlinx.serialization.json.JsonObject
import splice.core.turn.Usage

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
) {
    fun plusRound(u: Usage) = RoundUsage(
        lastInput = u.inputTokens,
        lastCached = u.cachedTokens,
        outSum = outSum + u.outputTokens,
        reasoningSum = reasoningSum + u.reasoningTokens,
    )

    fun toUsage() = Usage(
        inputTokens = lastInput,
        outputTokens = outSum,
        cachedTokens = lastCached,
        reasoningTokens = reasoningSum,
    )
}
