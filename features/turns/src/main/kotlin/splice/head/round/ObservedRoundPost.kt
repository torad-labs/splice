// NEW: redirected source generation preserves the raw-round accounting choke point.
package splice.head.round

import splice.core.perf.TurnPerf
import splice.upstream.PostingTurnRow
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundBody
import splice.upstream.RoundBodyPost
import splice.upstream.RoundResult
import splice.upstream.sse.WireSink

/** The post an interceptor receives. A body handed over as a tree reaches the transport as that tree, and
 *  text an interceptor composed goes as text; both pass the raw-round observation once. */
internal class ObservedRoundPost(
    private val dispatch: PostRoundToSink,
    private val ordinary: PostRound,
    private val observation: RawRoundObserver?,
    override val perf: TurnPerf?,
    override val postingRow: PostingTurnRow? = null,
) : RedirectableRoundPost, RoundBodyPost {
    override suspend fun invoke(bodyJson: String): RoundResult = post(RoundBody.Text(bodyJson))

    override suspend fun into(bodyJson: String, sink: WireSink): RoundResult = postInto(RoundBody.Text(bodyJson), sink)

    override suspend fun post(body: RoundBody): RoundResult = observed(ordinary(body))

    override suspend fun postInto(body: RoundBody, sink: WireSink): RoundResult = observed(dispatch(body, sink))

    /** An ending has no outcome to observe: the raw-round accounting sees rounds that produced one. */
    private fun observed(result: RoundResult): RoundResult {
        if (result is RoundResult.Outcome) observation?.invoke(result.outcome)
        return result
    }
}
