// NEW: optional provider-owned round translation preserves the gateway's ordinary tool execution path.
package splice.upstream

import splice.core.perf.TurnPerf
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
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

    /** The perf row of the turn this post serves, for a round that outlives it. Null where no turn row exists. */
    public val postingRow: PostingTurnRow? get() = null
}

/**
 * The perf row of a turn that returned while a round it posted was still streaming. The round's usage arrives
 * only at its terminal, so the row waits for it and carries it. The client never waits: the tool call and the
 * message's end have already left. [hold] is called before the turn returns; the release it gives is called
 * exactly once, from wherever the round settles.
 */
public fun interface PostingTurnRow {
    public fun hold(): RowRelease
}

/** Ends one [PostingTurnRow.hold]: [usage] is the turn's whole usage with the round in it, or null when the round
 *  ended with nothing to bill (it was cut, it failed, or the head stopped), so the row keeps what the turn returned. */
public fun interface RowRelease {
    public fun release(usage: Usage?)
}

/**
 * A round post that takes the body as the head holds it, so a tree reaches the transport as that tree and
 * nothing renders text nobody reads. The turn's own post declares it; a post without it is handed text.
 *
 * It is a separate type and never a default overload on [InterceptedRoundPost]: Kotlin's `by` delegation
 * forwards a default method to the delegate, so a wrapper that overrides the text form would be skipped
 * on the body form without a word. A wrapper declares this only when it wraps both.
 */
public interface RoundBodyPost {
    public suspend fun post(body: RoundBody): TurnOutcome

    public suspend fun postInto(body: RoundBody, sink: WireSink): TurnOutcome
}

/** Optional per-turn wrapper around one upstream round. Null means the established direct path. */
public fun interface RoundInterceptor {
    /** True only when this prepared request answers a source round that is still live. */
    public fun resumesSource(): Boolean = false

    /** One accepted recovery, before its next dispatch. Its private request additions are not client history.
     *  The default keeps interceptors without durable replay state unchanged. */
    public fun reanchor(round: ReanchorRound): Unit = Unit

    public suspend fun intercept(
        bodyJson: String,
        sink: WireSink,
        postRound: InterceptedRoundPost,
    ): TurnOutcome
}

/** An interceptor that reads the round as the head holds it, so a tree is read as that tree and never
 *  rendered and parsed back. One without it is handed text. Separate from [RoundInterceptor] for the
 *  reason [RoundBodyPost] is. */
public interface RoundBodyInterceptor {
    public suspend fun intercept(body: RoundBody, sink: WireSink, postRound: InterceptedRoundPost): TurnOutcome
}
