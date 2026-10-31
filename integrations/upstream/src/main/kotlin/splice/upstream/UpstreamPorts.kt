// PORT-OF: splice/spi/UpstreamClient.kt (RetryBackoff, StreamStart) @ 3879c4c — invariants unchanged: declarations only; the curve itself lives on UpstreamTransport as defaultBackoff. Failure-handling slice 4 took DnsBackoff: RetryEngine names every wait after a path that did not answer.
//
// The roles UpstreamClient itself takes (HD-25) — the runners' backoff curve and the handoff notification.
//
// THE CRITERION, so the split from TurnPorts.kt is legible rather than arbitrary: TurnPorts.kt
// holds the turn-path roles that CROSS A MODULE BOUNDARY (a dialect or :daemon-head wires them), and
// its header makes a point of what is deliberately not there. These two are wired by nobody
// outside :upstream — they are the upstream POST's own vocabulary — so they get their own
// declaration file rather than diluting that one's charter. Same package either way; no import in
// the tree changes.
package splice.upstream

/**
 * The pause between two connect-phase attempts, given the attempt index and a FLOOR in ms.
 *
 * A server `Retry-After` rides in as `minDelayMs` (G3), so the curve may stretch to obey upstream pushback but may
 * never undercut it.
 * The default is the exponential ±10% jitter curve; the jitter is not decoration, synchronized
 * retry herds re-collide without it.
 */
public fun interface RetryBackoff {
    public suspend operator fun invoke(attempt: Int, minDelayMs: Long): Unit
}

/**
 * Fired once the upstream stream is handed off — a 2xx arrived and the body is flowing.
 *
 * The moment matters more than the notification: it is the boundary after which a retry becomes a
 * RE-ISSUE, governed by RetryEngine's fresh-reissue budget and by [ClientFrameEmitted], rather than an
 * ordinary connect-phase attempt.
 */
public fun interface StreamStart {
    public operator fun invoke()
}
