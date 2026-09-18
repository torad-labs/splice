// PORT-OF: splice/spi/UpstreamClient.kt (UpstreamAuthMissing, StreamTornBeforeClient, UpstreamFailed) @ 3879c4c — invariants unchanged: same package, so every `import splice.spi.UpstreamFailed` in the tree resolves untouched.
//
// The upstream call's THROWN vocabulary — the transport, authentication and HTTP FAILURES shared
// by the retry loop and the turn driver. Each one is dispositioned below against
// kt-no-exception-as-outcome: a failure the caller can only report, never a refusal it branches on.
//
// Here rather than beside the loop because after the HD-25 split no single file owns them any
// more: [UpstreamFailed] is thrown by RetryPolicy.kt's give-up AND by RateLimitCooldown.kt's
// fail-fast, [StreamTornBeforeClient] is thrown by WsRoundRunner.kt and by :gateway's turn driver,
// while [UpstreamAuthMissing] is raised by UpstreamClient.kt itself. The whole-turn exhaustion
// signal is NOT here any more: V4-114 made it UpstreamPost.TurnWaitExhausted, a value on post()'s
// return type, because it is the one ending this loop DECIDES rather than suffers.
// Same package, so every existing `import splice.spi.UpstreamFailed` resolves unchanged.
package splice.spi

// V4-114 disposition: no caller recovers. Every catch site ends the turn in an error terminal —
// TurnFailures.kt:26 (the per-turn boundary, same chain as IOException), TurnKnownEnd.kt:31
// (emitError AUTHENTICATION + login hint), TurnDriver.kt:149 (marks the credential missing, then
// still fails the turn). No alternative outcome exists.
// ast-grep-ignore: kt-no-exception-as-outcome -- 2026-09-17: a local credential failure, never a branch
public class UpstreamAuthMissing : RuntimeException("no upstream credentials")

/** G5 reachability (review 2026-07-19): a transport tear BEFORE any client frame, rethrown by the
 *  turn driver THROUGH the translators (whose catch lists deliberately swallow IOException into
 *  the honest terminal — correct post-frame, but it made the reissue unreachable). Plain
 *  RuntimeException so no translator catch matches; the original tear rides as [cause] so
 *  isRetryableTransport's cause-chain walk classifies it. */
// V4-114 disposition: a transport tear. Deliberately a plain RuntimeException so the translators'
// IOException catches cannot swallow it, and TurnFailures.kt:30 / TurnConnEnd.kt:26 then fold it
// into the SAME conn-reset error terminal as a raw IOException — that shared arm IS the proof it is
// a failure and not a branch.
// ast-grep-ignore: kt-no-exception-as-outcome -- 2026-09-17: a transport tear, never a branch
public class StreamTornBeforeClient(cause: Throwable) :
    RuntimeException("stream torn before first client frame", cause)

// V4-114 disposition: the upstream host's own HTTP failure after retries — not a decision this side
// made. TurnFailures.kt:28 / TurnKnownEnd.kt:44 classify [status]/[body] and emit an error terminal;
// no caller continues the turn.
// ast-grep-ignore: kt-no-exception-as-outcome -- 2026-09-17: the upstream host's failure, never a branch
public class UpstreamFailed(
    public val body: String,
    public val status: Int? = null,
) : RuntimeException("upstream failed after retries (status=$status)")
