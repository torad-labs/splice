// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnFailures, TurnDriver.classifyZeroEventFailure)
// @ 86f1411 — invariants unchanged: the per-turn error boundary and the failure-message shaping
// around it. The G2 zero-event reclassifier lives in ZeroEventFailure.kt (concentration, 2026-08-19).
package splice.head.turn

import kotlinx.coroutines.CancellationException
import splice.upstream.Provider
import splice.upstream.transport.TransportFailureReason
import java.io.IOException

/** The per-turn error boundary and the failure-message shaping around it. */
internal class TurnFailures(
    private val provider: Provider,
) {
    /** Captures every failure [TurnEnding.emitFailure] dispatches on: I/O and the runtime failures a gateway bug
     *  throws (bad argument or state, missing element, cast, unsupported operation, arithmetic), so a defect
     *  out of the turn ends in an error frame and a perf row, never a truncated 200 (a bad base_url parse and a Ktor internal
     *  state error were the first two). The list is explicit because detekt refuses a generic catch.
     *  An Error is not caught. The stream and collect entries share ONE boundary. */
    inline fun <R> catchingTurnFailure(block: () -> R): Result<R> =
        try {
            Result.success(block())
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            // CancellationException extends IllegalStateException — rethrown BEFORE IllegalStateException so a
            // cancelled turn actually stops; stream()/collect() seal the emitter then rethrow.
            throw e
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: IllegalStateException) {
            Result.failure(e)
        } catch (e: NoSuchElementException) {
            Result.failure(e)
        } catch (e: ClassCastException) {
            Result.failure(e)
        } catch (e: UnsupportedOperationException) {
            Result.failure(e)
        } catch (e: ArithmeticException) {
            Result.failure(e)
        }

    /** V4-164: the socket's own account, never Throwable.message — the JDK client's refused connect
     *  carries a null one, which reached Claude Code as "upstream connection failed (no detail)". */
    fun connectionResetMessage(error: Throwable): String =
        TransportFailureReason.of(error, provider.upstreamUrl)

    /** G19-consistent per-head hint — every AUTHENTICATION surface uses the SAME provider-threaded
     *  command (review 2026-07-19: two paths still hardcoded "claudex login" on non-codex heads). */
    fun loginHint(): String =
        if (provider.loginCommand.isNotEmpty()) "; run: ${provider.loginCommand}" else ""
}
