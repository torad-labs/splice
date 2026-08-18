// PORT-OF: splice/app/Daemon.kt (DaemonBoundary) @ ed5c868 — invariants unchanged: best-effort
// isolation at daemon/head boundaries. A cross-cutting boundary used by call sites across the
// daemon, head boot, control bind and Main's stop timeout, so it stays at root package: Main.kt
// and DaemonTest already reference it unqualified and need no edit for this move.
package splice.app

import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Best-effort isolation at daemon/head boundaries without turning cancellation or fatal JVM
 * failures into a merely degraded head. Expected I/O and assembly failures become [Result]
 * failures; cancellation and [Error] always escape.
 *
 * A constructed collaborator rather than a free function (Kotlin style law, 2026-08-15); every
 * user holds one. `inline` is LOAD-BEARING and must stay: six call sites pass a lambda, and the
 * non-local-return semantics plus the absent allocation are part of the boundary's contract.
 */
internal class DaemonBoundary {
    internal inline fun <T> runCatchingDaemonBoundary(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        Result.failure(failure)
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    } catch (failure: IllegalStateException) {
        Result.failure(failure)
    }

    /** The operator-facing reason a boundary-captured failure carries, for the rare throwable with
     *  no message. Named by BRANCH over the three classes [runCatchingDaemonBoundary] can actually
     *  produce — the catch list above IS the closed set — rather than by reflecting on the runtime
     *  class, which is what this used to do. */
    internal fun reason(failure: Throwable): String = failure.message ?: when (failure) {
        is IOException -> "IOException"
        is IllegalArgumentException -> "IllegalArgumentException"
        is IllegalStateException -> "IllegalStateException"
        else -> "boundary failure"
    }
}
