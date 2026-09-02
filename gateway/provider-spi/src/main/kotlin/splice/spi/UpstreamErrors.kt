// PORT-OF: splice/spi/UpstreamClient.kt (UpstreamAuthMissing, StreamTornBeforeClient, UpstreamFailed) @ 3879c4c — invariants unchanged: same package, so every `import splice.spi.UpstreamFailed` in the tree resolves untouched.
//
// The upstream call's THROWN vocabulary — the three exceptions the retry loop, the cooldown and
// the turn driver end a turn with.
//
// Here rather than beside the loop because after the HD-25 split no single file owns them any
// more: [UpstreamFailed] is thrown by RetryPolicy.kt's give-up AND by RateLimitCooldown.kt's
// fail-fast, [StreamTornBeforeClient] is thrown by WsRoundRunner.kt and by :gateway's turn driver,
// and only [UpstreamAuthMissing] is still raised by UpstreamClient.kt itself. Same package, so
// every existing `import splice.spi.UpstreamFailed` resolves unchanged.
package splice.spi

public class UpstreamAuthMissing : RuntimeException("no upstream credentials")

/** G5 reachability (review 2026-07-19): a transport tear BEFORE any client frame, rethrown by the
 *  turn driver THROUGH the translators (whose catch lists deliberately swallow IOException into
 *  the honest terminal — correct post-frame, but it made the reissue unreachable). Plain
 *  RuntimeException so no translator catch matches; the original tear rides as [cause] so
 *  isRetryableTransport's cause-chain walk classifies it. */
public class StreamTornBeforeClient(cause: Throwable) :
    RuntimeException("stream torn before first client frame", cause)

public class UpstreamFailed(
    public val body: String,
    public val status: Int? = null,
) : RuntimeException("upstream failed after retries (status=$status)")
