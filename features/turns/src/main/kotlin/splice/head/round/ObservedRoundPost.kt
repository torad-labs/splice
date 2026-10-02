// NEW: redirected source generation preserves the raw-round accounting choke point.
package splice.head.round

import splice.core.turn.TurnOutcome
import splice.upstream.RedirectableRoundPost
import splice.upstream.sse.WireSink

internal class ObservedRoundPost(
    private val dispatch: PostRoundToSink,
    private val ordinary: splice.upstream.InterceptedRoundPost,
    private val observation: RawRoundObserver?,
) : RedirectableRoundPost {
    override suspend fun invoke(bodyJson: String): TurnOutcome {
        val outcome = ordinary(bodyJson)
        observation?.invoke(outcome)
        return outcome
    }

    override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
        val outcome = dispatch(bodyJson, sink)
        observation?.invoke(outcome)
        return outcome
    }
}
