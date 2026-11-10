// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnFailures, TurnDriver.classifyZeroEventFailure)
// @ 86f1411 — invariants unchanged: the per-turn error boundary and the failure-message shaping
// around it. The G2 zero-event reclassifier lives in ZeroEventFailure.kt (concentration, 2026-08-19).
package splice.head.turn

import splice.core.util.Cancellables
import splice.upstream.Provider
import splice.upstream.transport.TransportFaultLine

/** The per-turn error boundary and the failure-message shaping around it. */
internal class TurnFailures(
    private val provider: Provider,
) {
    /** Captures every failure but cancellation and an Error, so a defect out of the turn ends in an error frame, a log
     *  line and a perf row ([TurnEnding.emitFailure]), never a truncated 200. TOTAL since the 2026-11-09 audit: the
     *  boundary used to name seven exception types, and anything else (a NullPointerException, an
     *  IndexOutOfBoundsException, a ConcurrentModificationException, a checked exception Kotlin never declares)
     *  skipped both the error frame and the cancellation seal and closed the 200 cleanly with no row. The same total
     *  shape the control plane's routes have had since 2026-09-23 (ControlGuard, RouteFailure). Cancellation is
     *  rethrown so a cancelled turn stops and stream()/collect() seal it; an Error is rethrown, never masked. The
     *  stream and collect entries share ONE boundary. */
    inline fun <R> catchingTurnFailure(block: () -> R): Result<R> = Cancellables.runCatchingBestEffort(block)

    /** V4-164: the socket's own account, never Throwable.message — the JDK client's refused connect
     *  carries a null one. It goes to the log; the client reads the line for the connection's cause. */
    fun connectionResetMessage(error: Throwable): String =
        TransportFaultLine.of(error, provider.upstreamUrl)
}
