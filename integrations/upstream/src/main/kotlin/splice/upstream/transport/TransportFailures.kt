// PORT-OF: splice/spi/UpstreamClient.kt (Transport's cause-chain classification) @ 3879c4c — invariants unchanged: the retryable set, the CONNECT/POST_SEND phase split and the MAX_CAUSE_DEPTH bound are the original ones; only the receiver moved.
//
// Throwable -> verdict, and nothing else: no state, no budgets, no clock (HD-25). Was the
// classification half of UpstreamClient.Transport; only the receiver moved.
//
// What deliberately did NOT come along: `canReissueStream`. It reads three LOOP-BUDGET facts
// (handoff, first-frame, re-issue count) and only ever sat here because its one helper
// (isRetryableTransport) did — it now lives in RetryPolicy.kt with the rest of the loop's
// decisions. The client CONSTRUCTION half is UpstreamTransport.kt.
//
// V4-66 (2026-09-16) — the ALLOWLIST still classifies; it no longer GATES the connect-phase
// retry. [isRetryableTransport] and [classifyTransport] are unchanged, because stream REISSUE
// (G5) and the DNS backoff schedule still ask them and a widened set there would re-issue a
// stream on a failure nobody characterised. What changed is [rethrowUnlessRetryableTransport]:
// an unclassified throwable used to rethrow on the first attempt with the whole budget unspent,
// which is how a BARE java.io.IOException from the JDK header parser ("HTTP/1.1 header parser
// received no bytes", measured live 2026-09-16 on claude-deepseek, attempts=1, 234ms) ended a
// turn that a retry would have completed. The operator law is retry on any error with different
// levels of retry and escalation plus backoff — V4-62 deleted the equivalent gate on the STATUS
// side; this row deletes it here. The give-up gates are unchanged and still decide.
package splice.upstream.transport

import splice.core.util.Cancellables
import splice.upstream.DnsBackoff
import splice.upstream.RetryBackoff
import kotlin.coroutines.cancellation.CancellationException

/**
 * A test applied to each link of a Throwable's cause chain — Ktor wraps engine exceptions, so the
 * class that decides "retryable transport" or "DNS" is never the one thrown.
 *
 * Shared so the retryable-transport and DNS-only rules do not each re-walk the chain, and so the
 * MAX_CAUSE_DEPTH bound is written once rather than trusted twice.
 */
public fun interface CausePredicate {
    public operator fun invoke(cause: Throwable): Boolean
}

/** G16: which side of the request write the transport failure happened on — CONNECT never
 *  got a byte onto the wire (DNS/refused/connect-timeout); POST_SEND may have already
 *  handed the upstream a full request (SocketException reset, socket-level timeout) —
 *  retrying that one risks a double token burn, so it needs a distinct log class. */
internal enum class TransportFailurePhase { CONNECT, POST_SEND }

internal class TransportFailures {
    /** The cause chain as a bounded sequence — [e] itself, then its causes, at most
     *  MAX_CAUSE_DEPTH links (Ktor wraps engine exceptions, so the class that decides
     *  "retryable transport" or "DNS" is never the one thrown). THE walk: every rule below
     *  reads the chain through this one, lazily, so the bound is written once rather than
     *  trusted twice and the phase and DNS rules cannot drift apart. */
    private fun causeChain(e: Throwable): Sequence<Throwable> =
        generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH)

    /** Does any link of the chain satisfy [predicate]? The boolean special case of the same
     *  walk [classifyTransport] maps over — the loop used to be written out twice, and the
     *  copy drifted no further than luck. */
    private fun causeChainMatches(e: Throwable, predicate: CausePredicate): Boolean =
        causeChain(e).any { predicate(it) }

    /** Per-node classification, split out of [classifyTransport] so the loop shape and the
     *  allowlist `when` each stay under detekt's CyclomaticComplexMethod ceiling. */
    private fun transportPhaseOf(t: Throwable): TransportFailurePhase? = when {
        t is java.nio.channels.UnresolvedAddressException ||
            t is java.net.UnknownHostException ||
            t is java.net.ConnectException ||
            t is io.ktor.client.network.sockets.ConnectTimeoutException -> TransportFailurePhase.CONNECT
        t is java.net.SocketException ||
            t is java.net.SocketTimeoutException ||
            t is io.ktor.client.network.sockets.SocketTimeoutException -> TransportFailurePhase.POST_SEND
        else -> null
    }

    /** The first link of [causeChain] that classifies as a transport failure — the retryable
     *  set by phase instead of a bare Boolean. Deliberately conservative — everything else
     *  (TLS trust failures, protocol errors, the HttpTimeout plugin's overall budget) still
     *  fails the turn. Not an added/removed exception type — a pure reclassification of the
     *  existing retryable set (G16). */
    internal fun classifyTransport(e: Throwable): TransportFailurePhase? =
        causeChain(e).firstNotNullOfOrNull { transportPhaseOf(it) }

    /**
     * Connection-phase failures worth a silent retry: name resolution, TCP connect/reset,
     * socket timeouts. Deliberately conservative — everything else (TLS trust failures,
     * protocol errors, the HttpTimeout plugin's overall budget) still fails the turn.
     * Walks the cause chain because Ktor wraps engine exceptions.
     */
    internal fun isRetryableTransport(e: Throwable): Boolean = classifyTransport(e) != null

    /** DNS-class only (name resolution never got an address) — a real resolver blip runs
     *  closer to 1-4s than a TCP refusal, so it gets its own schedule instead of racing the
     *  generic curve (G14; Envoy dns_failure_refresh_rate shape). */
    internal fun isDnsFailureTransport(e: Throwable): Boolean = causeChainMatches(e) { t ->
        t is java.nio.channels.UnresolvedAddressException || t is java.net.UnknownHostException
    }

    /** Transport-error backoff (G14): DNS-class failures run the dedicated 1s/2s/4s schedule
     *  instead of the generic curve. The caller times this (PostContext.timedBackoff) so this
     *  file never names TurnPerf. */
    internal suspend fun backoffTransportError(
        error: Throwable,
        attempt: Int,
        dnsBackoff: DnsBackoff,
        backoff: RetryBackoff,
    ) {
        if (isDnsFailureTransport(error)) {
            dnsBackoff(attempt)
        } else {
            backoff(attempt, 0L)
        }
    }

    /** A transport error thrown BEFORE stream handoff retries; once handed off, past the
     *  deadline, or on the last attempt, rethrow. [deadlineHit] folds stream-handoff and the
     *  loop's own deadlineExceeded — this file does not own the clock, and that fold is what
     *  keeps a torn stream AFTER the client saw bytes from ever being re-POSTed here (duplicate
     *  output is the one failure a retry must not manufacture). Returns the surviving phase so
     *  the caller can label POST_SEND (G16 possible-duplicate).
     *
     *  V4-66: THE CLASS NO LONGER DECIDES. Every throwable that reaches this seam is retryable
     *  once the two give-up gates above have declined, and an UNCLASSIFIED one is POST_SEND —
     *  the conservative half, because this seam cannot see whether the request reached the
     *  wire, so the honest label is "this retry may double-burn". The alternative that shipped
     *  until today — `classifyTransport(e) ?: throw e` — made a six-name allowlist the gate on a
     *  budget it was never meant to gate: the set answers "which failures may be RE-ISSUED as a
     *  stream", and a bare IOException is not evidence about that question at all.
     *
     *  Cancellation is not weather. It is checked FIRST, so a cancelled turn aborts even with a
     *  full budget and can never become a retry — the transport path cannot currently deliver one
     *  (Cancellables.runCatchingCancellable captures IOException, SerializationException and
     *  IllegalArgumentException only, so a CancellationException propagates before this seam),
     *  which is exactly why the guard is written here rather than assumed from the call site.
     *
     *  WHAT ACTUALLY REACHES THIS SEAM, read from that catch list rather than assumed: an
     *  IOException, a SerializationException (a truncated or malformed upstream body — weather,
     *  and now retried, which is the point), and an IllegalArgumentException (a bad base_url
     *  parse — permanent, and the whole default budget costs about 1.5s on the 200ms doubling
     *  curve to discover, which is V4-62's own trade). Everything else — an IllegalState, an NPE,
     *  any other RuntimeException — is never captured at all and still fails the turn on attempt
     *  one, unchanged: retrying our own defects three times was never the law's intent. */
    internal fun rethrowUnlessRetryableTransport(
        e: Throwable,
        deadlineHit: Boolean,
        lastAttempt: Boolean,
    ): TransportFailurePhase {
        if (e is CancellationException) throw e
        val giveUp = deadlineHit || lastAttempt
        if (giveUp) throw e
        return classifyTransport(e) ?: TransportFailurePhase.POST_SEND
    }

    /** Inline so a suspend execute inside [block] inlines into the caller's coroutine
     *  (Cancellables.runCatchingCancellable takes a non-suspend lambda). */
    internal inline fun <R> catchCancellable(block: () -> R): Result<R> =
        Cancellables.runCatchingCancellable(block)
}

internal const val MAX_CAUSE_DEPTH = 8
