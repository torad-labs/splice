// PORT-OF: splice/gateway/head/TurnDriver.kt (RoundUsage, and FoldRunner's private nested
// RoundCursor) @ 86f1411 — invariants unchanged: the two value types the round loop passes around —
// the cumulative-usage accumulator and the loop cursor. RoundCursor is promoted from a private
// nested class to internal top-level so it can serve as the return/parameter type between
// FoldRunner and FoldRounds once the fold-continuation check lives in its own file.
package splice.head.round

import kotlinx.serialization.json.JsonObject
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.UsageHistory
import splice.core.turn.UsageOrigin
import splice.core.turn.UsageRequest

/** One position in the round loop: the body to POST plus the two round counters. Serves BOTH
 *  directions — passed INTO FoldRounds.nextRoundBody as the current cursor and returned as the
 *  next one (detekt 2026-07-24: the 8-arg form tripped LongParameterList). */
internal data class RoundCursor(val body: JsonObject, val roundIndex: Int, val searchIndex: Int)

/** The input side of the round-usage law: CUMULATIVE snapshots, because each continuation re-sends the
 *  entire conversation and round N already contains round N-1's. */
internal data class CumulativeInput(
    val total: Long = 0,
    val cached: Long = 0,
    /** V4-85: the cache-WRITE half of [total], under the same cumulative law — a continuation
     *  re-sends the whole conversation, so round N's cache_creation already contains round N-1's. */
    val cacheWrite: Long = 0,
)

/** The round-usage law, ONE implementation for both runners (2026-07-20, unified in the
 *  code-review 2026-07-24): each continuation re-sends the ENTIRE conversation, so input/cached
 *  are CUMULATIVE — round N already includes round N-1's; summing them (the old `Usage.plus`)
 *  inflated the client-visible prompt up to ~Nx, firing the context bar / autocompact early.
 *  Only output/reasoning genuinely accrue per round. */
internal data class RoundUsage(
    val input: CumulativeInput = CumulativeInput(),
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    /** Whether the final request exists, each round this law superseded (billed as a request of its
     *  own, [splice.core.turn.AbsorbedRounds]: the cumulative law keeps the context and this keeps the
     *  bill), the source rounds the turn cut while they streamed (they accrue), and the code-mode facts
     *  that ride to the terminal: the client reads [UsageOrigin.clientContext] only when the turn's
     *  final round measured no input. */
    val origin: UsageOrigin = UsageOrigin(history = UsageHistory(request = UsageRequest.NONE)),
    /** Which token fields the provider reported. */
    val reported: Set<UsageField> = emptySet(),
) {
    fun plusRound(u: Usage): RoundUsage {
        val total = toUsage().followedBy(u)
        return RoundUsage(
            input = CumulativeInput(total.inputTokens, total.cachedTokens, total.cacheWriteTokens),
            outputTokens = total.outputTokens,
            reasoningTokens = total.reasoningTokens,
            origin = total.origin,
            reported = total.reported,
        )
    }

    /** A failed continuation is a distinct request too: never inherit the previous round's bill. */
    fun plusTerminal(u: Usage): RoundUsage = plusRound(u)

    fun toUsage() = Usage(
        inputTokens = input.total,
        outputTokens = outputTokens,
        cachedTokens = input.cached,
        reasoningTokens = reasoningTokens,
        cacheWriteTokens = input.cacheWrite,
        origin = origin,
        reported = reported,
    )
}
