// NEW: optional provider-owned round translation preserves the gateway's ordinary tool execution path.
package splice.spi

import splice.core.turn.TurnOutcome

/** One upstream dispatch; an interceptor may invoke it again for a bounded local continuation. */
public fun interface InterceptedRoundPost {
    public suspend operator fun invoke(bodyJson: String): TurnOutcome
}

/** Optional per-turn wrapper around one upstream round. Null means the established direct path. */
public fun interface RoundInterceptor {
    public suspend fun intercept(
        bodyJson: String,
        sink: WireSink,
        postRound: InterceptedRoundPost,
    ): TurnOutcome
}
