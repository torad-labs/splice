// NEW: redirected source generation preserves the raw-round accounting choke point.
package splice.head.round

import splice.core.perf.TurnPerf
import splice.core.turn.TurnOutcome
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundBody
import splice.upstream.RoundBodyPost
import splice.upstream.sse.WireSink

/** The post an interceptor receives. A body handed over as a tree reaches the transport as that tree, and
 *  text an interceptor composed goes as text; both pass the raw-round observation once. */
internal class ObservedRoundPost(
    private val dispatch: PostRoundToSink,
    private val ordinary: PostRound,
    private val observation: RawRoundObserver?,
    override val perf: TurnPerf?,
) : RedirectableRoundPost, RoundBodyPost {
    override suspend fun invoke(bodyJson: String): TurnOutcome = post(RoundBody.Text(bodyJson))

    override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome = postInto(RoundBody.Text(bodyJson), sink)

    override suspend fun post(body: RoundBody): TurnOutcome {
        val outcome = ordinary(body)
        observation?.invoke(outcome)
        return outcome
    }

    override suspend fun postInto(body: RoundBody, sink: WireSink): TurnOutcome {
        val outcome = dispatch(body, sink)
        observation?.invoke(outcome)
        return outcome
    }
}
