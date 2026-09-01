// PORT-OF: splice/spi/UpstreamClient.kt (RetryBackoff, DnsBackoff, StreamStart) @ 3879c4c — invariants unchanged: declarations only; the curves themselves live on UpstreamTransport as defaultBackoff/defaultDnsBackoff.
//
// The roles UpstreamClient itself takes (HD-25) — two backoff curves and the handoff notification.
//
// THE CRITERION, so the split from TurnPorts.kt is legible rather than arbitrary: TurnPorts.kt
// holds the turn-path roles that CROSS A MODULE BOUNDARY (a dialect or :gateway wires them), and
// its header makes a point of what is deliberately not there. These three are wired by nobody
// outside :provider-spi — they are the upstream POST's own vocabulary — so they get their own
// declaration file rather than diluting that one's charter. Same package either way; no import in
// the tree changes.
package splice.spi

/**
 * The pause between two connect-phase attempts, given the attempt index and a FLOOR in ms.
 *
 * The floor is the whole reason this is not [DnsBackoff]: a server `Retry-After` rides in as
 * `minDelayMs` (G3), so the curve may stretch to obey upstream pushback but may never undercut it.
 * The default is the exponential ±10% jitter curve; the jitter is not decoration, synchronized
 * retry herds re-collide without it.
 */
public fun interface RetryBackoff {
    public suspend operator fun invoke(attempt: Int, minDelayMs: Long): Unit
}

/**
 * The pause after a DNS-class transport failure, given the attempt index.
 *
 * A separate role from [RetryBackoff] on measured evidence, not symmetry: a real resolver blip (the
 * kimi 07:00 burst, 37 `UnresolvedAddressException` turns) outlasts the generic 200/400/800ms curve,
 * so this one runs 1s/2s/4s. It takes NO floor, and that absence is the type telling the truth — no
 * response was received, so there is no `Retry-After` to honour.
 */
public fun interface DnsBackoff {
    public suspend operator fun invoke(attempt: Int): Unit
}

/**
 * Fired once the upstream stream is handed off — a 2xx arrived and the body is flowing.
 *
 * The moment matters more than the notification: it is the boundary after which a retry becomes a
 * RE-ISSUE, governed by the small dedicated budget and by [ClientFrameEmitted], rather than an
 * ordinary connect-phase attempt.
 */
public fun interface StreamStart {
    public operator fun invoke()
}
