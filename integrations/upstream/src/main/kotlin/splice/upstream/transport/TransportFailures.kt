// PORT-OF: splice/spi/UpstreamClient.kt (Transport's cause-chain classification) @ 3879c4c — invariants unchanged: the retryable set, the CONNECT/POST_SEND phase split and the MAX_CAUSE_DEPTH bound are the original ones; only the receiver moved.
//
// Throwable -> verdict, and nothing else: no state, no budgets, no clock (HD-25). Was the
// classification half of UpstreamClient.Transport; only the receiver moved.
//
// What deliberately did NOT come along: `canReissueStream`, which read three LOOP-BUDGET facts. Failure-handling
// slice 4: whether a failure is sent again is RetryEngine's alone, and this file only says which side of the write it
// failed on ([TransportFailures.phaseOf]) and whether it is a tear ([TransportFailures.isRetryableTransport]). The
// client CONSTRUCTION half is UpstreamTransport.kt.
//
// V4-66 (2026-09-16) — the ALLOWLIST still classifies; it never GATES a retry of a failure before the stream's
// handoff. A BARE java.io.IOException from the JDK header parser ("HTTP/1.1 header parser received no bytes", measured
// live 2026-09-16 on claude-deepseek, attempts=1, 234ms) used to end a turn that a retry would have completed.
package splice.upstream.transport

import splice.core.util.Cancellables
import splice.core.util.WallClock
import splice.upstream.RetryBackoff
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter
import kotlin.coroutines.cancellation.CancellationException

/** G16: which side of the request write the transport failure happened on — CONNECT never
 *  got a byte onto the wire (DNS/refused/connect-timeout), or (V4-272) stalled before the body's
 *  end, so the upstream cannot hold the whole request; POST_SEND may have already
 *  handed the upstream a full request (SocketException reset, socket-level timeout) —
 *  retrying that one risks a double token burn, so it needs a distinct log class. */
internal enum class TransportFailurePhase { CONNECT, POST_SEND }

/** The failure chain both halves read: the verdict below and the name TransportFaultLine gives, so the
 *  two cannot read different chains. */
internal object FailureChain {
    /** [e] and its causes, at most MAX_CAUSE_DEPTH of them. OkHttp hands a Throwable its call thread caught
     *  over as IOException("canceled due to …") with that Throwable as the cause (RealCall.AsyncCall, 5.3.2:
     *  initCause), so a refused thread start is a link of this chain. */
    fun links(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH)

    /** V4-307: a thread start the host refused, in the JDK's own words (HotSpot, JDK 21). */
    fun refusedThreadStart(t: Throwable): Boolean =
        t is OutOfMemoryError && t.message?.startsWith(NATIVE_THREAD_REFUSED) == true

    private const val NATIVE_THREAD_REFUSED = "unable to create native thread"
}

internal class TransportFailures {
    /** The failure's links, bounded ([FailureChain.links]) — Ktor wraps engine exceptions, so the class
     *  that decides "retryable transport" or "DNS" is never the one thrown. Every rule below reads the
     *  links through this one, lazily, so the phase and DNS rules cannot drift apart. */
    private fun causeChain(e: Throwable): Sequence<Throwable> = FailureChain.links(e)

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
        // V4-272: found anywhere in the chain, because ktor wraps it in its own socket timeout, which
        // alone reads POST_SEND. V4-307: so is a thread start the host refused before the request was
        // written whole (RequestSendState). A refusal after that has no phase of its own: the upstream
        // may hold the whole request, so it keeps the POST_SEND every failure this seam cannot place gets
        // (rethrowUnlessRetryableTransport), and with it the possible-duplicate label.
        if (causeChain(e).any { it is RequestWriteStalled || it is RefusedBeforeSend }) {
            TransportFailurePhase.CONNECT
        } else {
            causeChain(e).firstNotNullOfOrNull { transportPhaseOf(it) }
        }

    /**
     * Connection-phase failures worth a silent retry: name resolution, TCP connect/reset,
     * socket timeouts. Deliberately conservative — everything else (TLS trust failures,
     * protocol errors, the HttpTimeout plugin's overall budget) still fails the turn.
     * Walks the cause chain because Ktor wraps engine exceptions.
     */
    internal fun isRetryableTransport(e: Throwable): Boolean = classifyTransport(e) != null

    /** Which side of the write [e] failed on, for the log's label (G16). V4-66: an UNCLASSIFIED failure is POST_SEND,
     *  the conservative half, because this seam cannot see whether the request reached the wire, so the honest label
     *  is "this retry may double-burn". Cancellation is not weather: it is rethrown, so a cancelled turn can never
     *  become a retry. Whether the failure is sent again is RetryEngine's, which reads it as a path that did not
     *  answer (TransportFaults). */
    internal fun phaseOf(e: Throwable): TransportFailurePhase {
        if (e is CancellationException) throw e
        return classifyTransport(e) ?: TransportFailurePhase.POST_SEND
    }

    /** Inline so a suspend execute inside [block] inlines into the caller's coroutine
     *  (Cancellables.runCatchingCancellable takes a non-suspend lambda). */
    internal inline fun <R> catchCancellable(block: () -> R): Result<R> =
        Cancellables.runCatchingCancellable(block)
}

internal const val MAX_CAUSE_DEPTH = 8
// How a client waits (default-parameter width flip, 2026-10-09). Failure-handling slice 4: RetryEngine says how long
// each wait is; this is where a post with no turn around it sleeps it, and the curve the head's round runners still
// pause on until they take the engine's steps too.

/** The generic retry curve: a doubling delay from [baseMs] capped at [capMs], widened by [jitterPct]. The defaults READ
 *  UpstreamTransport's public curve constants, so the curve a runner pauses on and
 *  [UpstreamTransport.defaultBackoff]'s cannot drift. */
public data class BackoffCurve(
    val baseMs: Long = DEFAULT_BACKOFF_BASE_MS,
    val capMs: Long = DEFAULT_BACKOFF_CAP_MS,
    val jitterPct: Int = DEFAULT_JITTER_PCT,
)

/** The retry curve as the operator's knobs (retryBackoffBaseMs, retryBackoffCapMs, retryBackoffJitterPct) say it is now,
 *  asked at each sleep, so a PATCH governs the next retry and not only the next restart. */
public fun interface LiveRetryCurve {
    public operator fun invoke(): BackoffCurve
}

/** How one client sleeps. HD-19: every sleep goes through [waiter], so a test replaces the WAIT without re-authoring
 *  what decides it, and a recording waiter turns a schedule into an assertion on a list. */
public class RetryPacing(
    curve: BackoffCurve = BackoffCurve(),
    private val waiter: Waiter = ProcessWaiter(),
    /** The curve read at every use; the fixed [curve] when no knob is live. */
    private val live: LiveRetryCurve = LiveRetryCurve { curve },
    private val backoff: RetryBackoff = UpstreamTransport().liveBackoff(waiter, live),
    /** The wall clock this client dates a failed answer by (failure-handling slice 3): a Retry-After date or a reset a
     *  provider names is read against the instant the answer came, and so is the hold it arms. One clock, so a head
     *  on a test's virtual time reads a recorded reset as the provider meant it. */
    internal val wall: WallClock = WallClock(System::currentTimeMillis),
) {
    /** Sleeps a wait RetryEngine named, for a post with no turn around it. */
    internal suspend fun wait(ms: Long) {
        waiter.wait(ms)
    }

    /** Sleeps the curve's delay for [attempt], or the server's own minimum when it asks for more: the round runners'
     *  pause until they take RetryEngine's steps. */
    internal suspend fun pause(attempt: Int, serverMinMs: Long) {
        backoff(attempt, serverMinMs)
    }
}
