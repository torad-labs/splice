// PORT-OF: splice/gateway/head/TurnDriver.kt (the fold/reanchor/single-round dispatch inlined in
// driveOneTurn) @ 86f1411 — invariants unchanged: which runner drives this turn — FoldRunner when
// fold-eligible, the single-round direct path when neither fold nor re-anchor nor search apply, and
// ReanchorRunner otherwise. Its own file (HD-24) is why TurnDriver can stop importing
// FoldRunner/ReanchorRunner directly. Constructed per turn; the two postRound shapes and finish are
// pre-bound closures over the caller's drive/self/turnJob, so this class stays decoupled from them.
package splice.head.round

import kotlinx.serialization.json.JsonObject
import splice.core.perf.TurnPerf
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.upstream.FoldPolicy
import splice.upstream.InterceptedRoundPost
import splice.upstream.PostingTurnRow
import splice.upstream.ReanchorPolicy
import splice.upstream.RoundBody
import splice.upstream.RoundBodyInterceptor
import splice.upstream.RoundInterceptor
import splice.upstream.sse.WireSink

/** Receives terminal outcomes from raw code-mode posts before the interceptor can expand them. */
internal fun interface RawRoundObserver {
    operator fun invoke(outcome: TurnOutcome)
}

/** Code-mode-only hooks around raw posts, grouped to keep the runner's constructor bounded. */
internal data class RoundInterception(
    val interceptor: RoundInterceptor? = null,
    val rawRoundObserved: RawRoundObserver? = null,
    /** The turn's row, for a round the interceptor leaves streaming past the turn. */
    val postingRow: PostingTurnRow? = null,
)

internal class RoundStrategy(
    private val emitter: WireSink,
    private val runners: RoundRunners,
    private val postRoundToSink: PostRoundToSink,
    private val postRound: PostRound,
    private val interception: RoundInterception = RoundInterception(),
) {
    suspend fun run(requestBody: JsonObject, fold: FoldPolicy?, reanchor: ReanchorPolicy?) {
        run(requestBody, fold, reanchor, null)
    }

    suspend fun run(
        requestBody: JsonObject,
        fold: FoldPolicy?,
        reanchor: ReanchorPolicy?,
        perf: TurnPerf?,
    ) {
        val recovery = observedReanchor(reanchor)
        val interceptedPost = PostRound { body -> intercept(body, emitter, postRound, perf) }
        val interceptedPostToSink = PostRoundToSink { body, sink ->
            intercept(body, sink, PostRound { posted -> postRoundToSink(posted, sink) }, perf)
        }
        if (fold != null) {
            runners.fold(emitter, interceptedPostToSink, recovery).run(requestBody, fold, perf)
        } else if (reanchor == null && !runners.searchesTools()) {
            runners.finishAlone(interceptedPost(RoundBody.Tree(requestBody)))
        } else {
            runners.reanchoring(interceptedPost).run(requestBody, recovery, perf)
        }
    }

    private fun observedReanchor(controller: ReanchorPolicy?): ReanchorPolicy? = controller?.let { policy ->
        ReanchorPolicy { round ->
            policy.continuationForFailure(round)?.also { interception.interceptor?.reanchor(round) }
        }
    }

    private fun observedPost(ordinary: PostRound, perf: TurnPerf?): InterceptedRoundPost =
        ObservedRoundPost(
            postRoundToSink,
            ordinary,
            if (interception.interceptor != null) interception.rawRoundObserved else null,
            perf,
            if (interception.interceptor != null) interception.postingRow else null,
        )

    /** Neither path renders [body]'s text. With no interceptor the round goes straight to the transport,
     *  which wants bytes, and ObservedRoundPost's observation is wired only when an interceptor exists.
     *  An interceptor that declares RoundBodyInterceptor reads the body as the head holds it; any other
     *  composes text, so it gets text, rendered here for that round only. */
    private suspend fun intercept(
        body: RoundBody,
        sink: WireSink,
        direct: PostRound,
        perf: TurnPerf?,
    ): TurnOutcome {
        val interceptor = interception.interceptor ?: return refusingCustomCalls(direct(body))
        val observed = observedPost(direct, perf)
        return if (interceptor is RoundBodyInterceptor) {
            interceptor.intercept(body, sink, observed)
        } else {
            interceptor.intercept(body.text, sink, observed)
        }
    }

    /** The direct path cannot execute a custom tool call, so a round that returns one ends the turn.
     *  An interceptor owns its own custom calls and never reaches this. */
    private fun refusingCustomCalls(outcome: TurnOutcome): TurnOutcome {
        if (outcome !is TurnOutcome.Success || outcome.customCalls.isEmpty()) return outcome
        val name = outcome.customCalls.first().name.ifEmpty { "<unnamed>" }
        return TurnOutcome.Failure(
            "upstream returned an unsupported custom tool call: $name",
            partial = TurnOutcome.PartialRound(usage = outcome.usage),
            cause = FailureCause.DIALECT_UNSUPPORTED,
            phase = FailurePhase.MID_OUTPUT,
        )
    }
}
