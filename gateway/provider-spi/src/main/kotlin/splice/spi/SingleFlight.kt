// PORT-OF: the refreshInflight single-flight pattern from server/src/auth/codex-oauth.mjs
// @ 4ca99f7 — invariant: N concurrent 401s trigger exactly ONE refresh; late callers await the
// in-flight result. Shared here so every RefreshableAuthProvider reuses it (the v29
// copies-drift lesson applied to auth). Mutex-guarded Deferred, not a naked boolean.
//
// COROUTINE HAZARD (has no Node analogue — Node's event loop can't cancel a peer): the leader runs
// `block()` on ITS OWN request coroutine. If that request is cancelled mid-refresh (client
// disconnect, watchdog), the leader's CancellationException must NOT be broadcast to followers
// awaiting the shared Deferred — those belong to DIFFERENT, still-healthy requests. So a leader
// that is cancelled completes the Deferred with a retryable marker and the followers RE-ELECT a new
// leader instead of dying with a cancellation they never asked for. A genuine refresh failure still
// propagates to every awaiter unchanged (exactly one refresh attempt per wave).
package splice.spi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class SingleFlight<T> {
    private val mutex = Mutex()
    private var inflight: CompletableDeferred<T>? = null

    /** Signals "the leader was cancelled — re-elect"; never surfaced to a caller. */
    private class LeaderAborted : Exception()

    /** Runs [block] once even under concurrent callers; everyone gets the same result. */
    public suspend fun run(block: suspend () -> T): T {
        while (true) {
            val (deferred, isLeader) = mutex.withLock {
                val existing = inflight
                if (existing != null) existing to false else CompletableDeferred<T>().also { inflight = it } to true
            }
            if (isLeader) return leadWith(deferred, block)
            follow(deferred)?.let { return it.value } // null => re-elect (leader aborted)
        }
    }

    /** Awaits the leader's result. Returns null iff the leader aborted (caller re-elects). */
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private suspend fun follow(deferred: CompletableDeferred<T>): Box<T>? =
        try {
            Box(deferred.await())
        } catch (e: Throwable) {
            // A CancellationException here is OUR OWN (a leader's abort surfaces as LeaderAborted,
            // never a broadcast cancellation) — propagate it. LeaderAborted => null (re-elect).
            if (e is CancellationException) throw e
            if (e is LeaderAborted) null else throw e // else a genuine refresh failure: awaiters see it
        }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private suspend fun leadWith(deferred: CompletableDeferred<T>, block: suspend () -> T): T =
        try {
            block().also { deferred.complete(it) }
        } catch (t: Throwable) {
            // ast-grep-ignore: kt-catch-swallows-cancellation — cancellation is NOT swallowed: it is
            // rethrown unconditionally below; only the SHARED deferred is shielded from it (followers
            // re-elect via LeaderAborted instead of inheriting our cancellation).
            deferred.completeExceptionally(if (t is CancellationException) LeaderAborted() else t)
            throw t
        } finally {
            // clear only if still ours — a re-elected leader may already own inflight
            mutex.withLock { if (inflight === deferred) inflight = null }
        }

    // Boxes the leader's result so `null` can mean "re-elect" distinctly from a nullable T value.
    private data class Box<out V>(val value: V)
}
