// PORT-OF: splice/gateway/head/TurnDriver.kt (the fold/reanchor/single-round dispatch inlined in
// driveOneTurn) @ 86f1411 — invariants unchanged: which runner drives this turn — FoldRunner when
// fold-eligible, the single-round direct path when neither fold nor re-anchor nor search apply, and
// ReanchorRunner otherwise. Its own file (HD-24) is why TurnDriver can stop importing
// FoldRunner/ReanchorRunner directly. Constructed per turn; the two postRound shapes and finish are
// pre-bound closures over the caller's drive/self/turnJob, so this class stays decoupled from them.
package splice.gateway.round

import kotlinx.serialization.json.JsonObject
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.spi.FoldController
import splice.spi.InterceptedRoundPost
import splice.spi.ReanchorController
import splice.spi.RetryNotice
import splice.spi.RoundInterceptor
import splice.spi.ToolSearchController
import splice.spi.WireSink

/** Receives terminal outcomes from raw code-mode posts before the interceptor can expand them. */
internal fun interface RawRoundObserver {
    operator fun invoke(outcome: TurnOutcome)
}

/** Code-mode-only hooks around raw posts, grouped to keep the runner's constructor bounded. */
internal data class RoundInterception(
    val interceptor: RoundInterceptor? = null,
    val rawRoundObserved: RawRoundObserver? = null,
)

internal class RoundStrategy(
    private val key: String,
    private val log: LogSink,
    private val emitter: WireSink,
    private val signals: RunnerSignals,
    private val postRoundToSink: PostRoundToSink,
    private val postRound: PostRound,
    private val finish: FinishTurn,
    // Defaulted (not just nullable): the 8th required param tripped the constructor-length wall
    // (max 7 required) — always passed explicitly at the one call site (driveOneTurn).
    private val toolSearch: ToolSearchController? = null,
    private val interception: RoundInterception = RoundInterception(),
) {
    private val rounds = RoundSplice()

    suspend fun run(requestBody: JsonObject, fold: FoldController?, reanchor: ReanchorController?) {
        val notice = RetryNotice { log(it) }
        val interceptedPost = PostRound { body ->
            intercept(body, emitter, observeRawPost(InterceptedRoundPost(postRound::invoke)))
        }
        val interceptedPostToSink = PostRoundToSink { body, sink ->
            intercept(body, sink, observeRawPost(InterceptedRoundPost { posted -> postRoundToSink(posted, sink) }))
        }
        if (fold != null) {
            FoldRunner(
                emitter = emitter,
                key = key,
                log = notice,
                postRound = interceptedPostToSink,
                finish = finish,
                reanchor = reanchor,
                signals = signals,
                toolSearch = toolSearch,
            ).run(requestBody, fold)
        } else if (reanchor == null && toolSearch == null) {
            // DR-130: the runners salvage a failed round's own burn through withFailureSalvage
            // (DR-124); this path handed the raw outcome to finishTurn, which stamps ONLY
            // salvagedUsage — so the tokens the vendor billed went unrecorded. Every compact turn
            // came through here until 2026-09-02 (re-anchor was null for compact; it no longer is),
            // which made the most expensive turn class the one that recorded nothing. There are no
            // absorbed rounds on this path, so the accumulator is empty by construction and a
            // Success or a clean abandonment passes through untouched.
            finish(rounds.withFailureSalvage(interceptedPost(requestBody.toString()), RoundUsage()))
        } else {
            ReanchorRunner(
                key = key,
                log = notice,
                postRound = interceptedPost,
                finish = finish,
                signals = signals,
                toolSearch = toolSearch,
            ).run(requestBody, reanchor)
        }
    }

    private fun observeRawPost(post: InterceptedRoundPost): InterceptedRoundPost = InterceptedRoundPost { bodyJson ->
        val outcome = post(bodyJson)
        if (interception.interceptor != null) interception.rawRoundObserved?.invoke(outcome)
        outcome
    }

    private suspend fun intercept(
        bodyJson: String,
        sink: WireSink,
        post: InterceptedRoundPost,
    ): TurnOutcome {
        if (interception.interceptor != null) return interception.interceptor.intercept(bodyJson, sink, post)
        val outcome = post(bodyJson)
        if (outcome !is TurnOutcome.Success || outcome.customCalls.isEmpty()) return outcome
        val name = outcome.customCalls.first().name.ifEmpty { "<unnamed>" }
        return TurnOutcome.Failure(
            ErrorType.INVALID_REQUEST,
            "upstream returned an unsupported custom tool call: $name",
            partial = TurnOutcome.PartialRound(usage = outcome.usage),
        )
    }
}
