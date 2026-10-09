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
    /** Captures exactly the failure classes [TurnEnding.emitFailure] dispatches on: I/O and the two documented
     *  gateway-bug classes (IllegalArgument/
     *  IllegalState — a bad base_url parse, a Ktor internal state error), which previously escaped
     *  as a truncated 200 with no error frame (review 2026-07-19). The stream and collect entries
     *  share ONE boundary. */
    inline fun <R> catchingTurnFailure(block: () -> R): Result<R> =
        try {
            Result.success(block())
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            // CancellationException extends IllegalStateException — rethrown BEFORE it so a
            // cancelled turn actually stops; stream()/collect() seal the emitter then rethrow.
            throw e
        } catch (e: IllegalArgumentException) {
            // e.g. a URL-parse error from a bad base_url (review 2026-07-19: previously escaped
            // as a truncated 200 with no error frame — emitFailure's branch was unreachable)
            Result.failure(e)
        } catch (e: IllegalStateException) {
            // e.g. an IllegalState out of Ktor internals — same escape class as above
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
