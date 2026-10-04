// NEW: optional provider-owned round translation preserves the gateway's ordinary tool execution path.
package splice.upstream

import splice.core.perf.TurnPerf
import splice.core.turn.TurnOutcome
import splice.upstream.sse.WireSink

/** One upstream dispatch; an interceptor may invoke it again for a bounded local continuation. */
public fun interface InterceptedRoundPost {
    public suspend operator fun invoke(bodyJson: String): TurnOutcome

    /** The turn's perf record, so an interceptor's own local work counts on the turn it serves. Null
     *  where no turn record exists: a bare post built by a test or a caller outside a turn. */
    public val perf: TurnPerf? get() = null
}

/** A dispatch that can send an independently owned upstream round into its current client sink. */
public interface RedirectableRoundPost : InterceptedRoundPost {
    public suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome
}

/** Optional per-turn wrapper around one upstream round. Null means the established direct path. */
public fun interface RoundInterceptor {
    /** True only when this prepared request answers a source round that is still live. */
    public fun resumesSource(): Boolean = false

    public suspend fun intercept(
        bodyJson: String,
        sink: WireSink,
        postRound: InterceptedRoundPost,
    ): TurnOutcome
}
