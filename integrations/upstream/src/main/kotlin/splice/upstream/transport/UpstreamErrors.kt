// PORT-OF: splice/spi/UpstreamClient.kt (UpstreamAuthMissing, StreamTornBeforeClient, UpstreamFailed) @ 3879c4c — invariants unchanged: same package, so every `import splice.upstream.transport.UpstreamFailed` in the tree resolves untouched.
//
// How an upstream call ENDS without delivering a round, as VALUES (kt-no-exception-as-outcome, 2026-10-09).
//
// These four were exceptions: thrown by the retry loop, the SSE reader and the translators, and caught at the
// turn's boundary. They are returned now. [UpstreamEnding] is what [UpstreamPost.Ended] and
// [splice.upstream.StreamRead.Ended] carry, and [splice.upstream.RoundResult.Ended] carries it up the round ports to
// the turn, where one end handler per variant writes the terminal. A caller that forgets one fails to compile.
// Same package, so every `import splice.upstream.transport.UpstreamFailed` resolves unchanged.
package splice.upstream.transport

import splice.core.usage.PlanLimit
import splice.core.wire.RateLimitReply

/** The sealed vocabulary of an upstream call's endings that are neither a delivered round nor a decision the
 *  loop makes (the whole-turn wait is [UpstreamPost.TurnWaitExhausted]). */
public sealed class UpstreamEnding

/** No local credentials to send. Never retried; the turn ends in an authentication error with the login hint. */
public class UpstreamAuthMissing : UpstreamEnding()

/** G5: a transport tear BEFORE any client frame, which the retry loop may re-issue. The original tear rides as [cause]
 *  so the transport classifiers walk it exactly as they walk a raw IOException. */
public class StreamTornBeforeClient(public val cause: java.io.IOException) : UpstreamEnding()

/** The upstream sent a frame over our own safety limit: [kind] says which of the two limits, [limit] its size, and
 *  [observed] how many characters the frame had reached when the read stopped (null when the reader did not count). */
public class SseFrameTooLarge(
    public val kind: String,
    public val limit: Int,
    public val observed: Int? = null,
) : UpstreamEnding() {
    public val text: String get() = "$kind exceeds the $limit-character safety limit"
}

/** V4-272: the upstream took no more of the request for [stalledMs], the head's firstByteTimeout, while
 *  bytes of it were still unacknowledged (V4-289: RequestWriteBound's watch reads the kernel's send queue;
 *  where it cannot, a write waiting in the kernel). "No more", never "none": it may have taken part of it.
 *  A SocketTimeoutException so every transport classifier still sees a socket timeout; its own type so it
 *  is not mistaken for a read one. Its text must not say "connect": ktor reads that word as a connect
 *  timeout (OkHttpEngine). The retry loop retries it on a new connection; a turn out of attempts ends like
 *  any other IOException. */
internal class RequestWriteStalled(public val stalledMs: Long, cause: Throwable) :
    java.net.SocketTimeoutException("the upstream took no more of the request for ${stalledMs}ms; the write stalled") {
    init {
        initCause(cause)
    }
}

/** The upstream host's own HTTP failure once retries are spent, or a local hold standing in for one. */
public class UpstreamFailed(
    public val body: String,
    public val status: Int? = null,
    /** V4-117: how many upstream ATTEMPTS the retry loop made before it gave up, stamped HERE
     *  because this is the loop's own fact at the moment the last attempt failed — the perf row
     *  records it as layers=<n>. It is a RECORD, not a prediction: the matrix could say a failure is
     *  entitled to four layers and the loop might have spent one. Zero when no retry was tried (the
     *  fail-fast and deadline paths), which is a real answer rather than a missing one. */
    public val layers: Int = 0,
    /** V4-419: the spent PLAN window this failure is about, when the upstream named one and its reset is
     *  still ahead: the 429 that met it (RetryRules.giveUp) and the followers held behind it
     *  (RateLimitCooldown.heldFailure). Stamped here because the ending is the only thing that
     *  crosses to the turn's ending, which records a plan-limit outcome and speaks the reset. Null for
     *  every other failure, a burst 429 with no named reset included. */
    public val planLimit: PlanLimit? = null,
    /** A synthetic cooldown refusal, not a response sent by the provider. */
    public val localHold: Boolean = false,
) : UpstreamEnding() {
    public var rateLimitReply: RateLimitReply? = null
        internal set
}
