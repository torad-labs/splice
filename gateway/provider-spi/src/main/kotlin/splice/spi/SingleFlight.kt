// PORT-OF: the refreshInflight single-flight pattern from server/src/auth/codex-oauth.mjs
// @ 4ca99f7 — invariant: N concurrent 401s trigger exactly ONE refresh; late callers await the
// in-flight result. Shared here so every RefreshableAuthProvider reuses it (the v29
// copies-drift lesson applied to auth).
//
// The block runs in a SupervisorJob scope OWNED BY THIS SingleFlight, decoupled from any single
// caller — like Node's shared refresh promise, which resolves regardless of which request started
// it. So:
//   - a caller cancelled mid-wait cancels ONLY its own await(), never the shared refresh (which
//     completes and caches for the survivors) — no cancellation is broadcast to peers;
//   - the block runs EXACTLY ONCE per wave no matter how many callers coalesce or get cancelled
//     (an earlier re-election design ran it once PER waiting follower — a wave-coalescing bug);
//   - a block failure surfaces to every awaiter via the shared Deferred but (SupervisorJob) never
//     cancels the scope or a sibling wave.
// A settled Deferred is not reused, so the next wave starts a fresh refresh.
package splice.spi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

public class SingleFlight<T>(
    // the refresh runs here, off the caller's coroutine. Injectable for tests (a test dispatcher);
    // ast-grep-ignore: main-no-hardcoded-dispatchers — this IS the injection seam, Dispatchers.Default
    // is only the production default for a background auth refresh.
    context: CoroutineContext = Dispatchers.Default,
) {
    // SupervisorJob on the RIGHT so it unconditionally wins the Job key — even if a caller passes a
    // context that carries its own Job (e.g. someScope.coroutineContext), isolation is preserved and
    // the injected context can't tie this scope's lifetime/failure-propagation to a caller's Job.
    private val scope = CoroutineScope(context + SupervisorJob())
    private val mutex = Mutex()
    private var inflight: Deferred<T>? = null

    /** Runs [block] once even under concurrent callers; everyone awaits the same shared result. */
    public suspend fun run(block: suspend () -> T): T {
        val shared = mutex.withLock {
            // reuse only a still-running refresh; a settled one means the next wave starts fresh.
            inflight?.takeIf { it.isActive } ?: scope.async { block() }.also { inflight = it }
        }
        // A caller's cancellation cancels THIS await only — the shared block keeps running in `scope`.
        return shared.await()
    }
}
