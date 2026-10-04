// NEW: redirected source generation preserves the raw-round accounting choke point.
package splice.head.round

import splice.core.perf.TurnPerf
import splice.core.turn.TurnOutcome
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundBody
import splice.upstream.sse.WireSink

internal class ObservedRoundPost(
    private val dispatch: PostRoundToSink,
    private val ordinary: splice.upstream.InterceptedRoundPost,
    private val observation: RawRoundObserver?,
    override val perf: TurnPerf?,
) : RedirectableRoundPost {
    override suspend fun invoke(bodyJson: String): TurnOutcome {
        val outcome = ordinary(bodyJson)
        observation?.invoke(outcome)
        return outcome
    }

    override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
        val outcome = dispatch(RoundBody.Text(bodyJson), sink)
        observation?.invoke(outcome)
        return outcome
    }
}
