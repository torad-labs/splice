// PORT-OF: the refreshInflight single-flight pattern from server/src/auth/codex-oauth.mjs
// @ 4ca99f7 — invariant: N concurrent 401s trigger exactly ONE refresh; late callers await the
// in-flight result. Shared here so every RefreshableAuthProvider reuses it (the v29
// copies-drift lesson applied to auth). Mutex-guarded Deferred, not a naked boolean.
package splice.spi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class SingleFlight<T> {
    private val mutex = Mutex()
    private var inflight: CompletableDeferred<T>? = null

    /** Runs [block] once even under concurrent callers; everyone gets the same result. */
    @Suppress("TooGenericExceptionCaught") // must complete the shared deferred on ANY failure
    public suspend fun run(block: suspend () -> T): T {
        val (deferred, isLeader) = mutex.withLock {
            val existing = inflight
            if (existing != null) {
                existing to false
            } else {
                val fresh = CompletableDeferred<T>()
                inflight = fresh
                fresh to true
            }
        }
        if (!isLeader) return deferred.await()
        return try {
            val result = block()
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            // ast-grep-ignore: kt-catch-swallows-cancellation — this does NOT swallow: it
            // completes the shared deferred exceptionally (so awaiters see the failure) and
            // then rethrows `t` unconditionally, propagating CancellationException intact.
            deferred.completeExceptionally(t)
            throw t
        } finally {
            mutex.withLock { inflight = null }
        }
    }
}
