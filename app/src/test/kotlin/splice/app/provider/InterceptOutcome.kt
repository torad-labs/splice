// NEW: a round interceptor driven the way these tests think of it: the post answers a turn outcome and the
// interceptor hands one back; a round that ended on an upstream ending fails the test.
package splice.app.provider

import splice.core.turn.TurnOutcome
import splice.upstream.RoundInterceptor
import splice.upstream.RoundResult
import splice.upstream.sse.WireSink

internal suspend fun RoundInterceptor.interceptOutcome(
    body: String,
    sink: WireSink,
    post: suspend (String) -> TurnOutcome,
): TurnOutcome = when (val result = intercept(body, sink) { RoundResult.Outcome(post(it)) }) {
    is RoundResult.Outcome -> result.outcome
    is RoundResult.Ended -> error("the round ended instead of producing an outcome: ${result.ending}")
}
