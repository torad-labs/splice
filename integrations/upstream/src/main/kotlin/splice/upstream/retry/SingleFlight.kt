// PORT-OF: the refreshInflight single-flight pattern from server/src/auth/codex-oauth.mjs
// @ pre-public-port-baseline — invariant: N concurrent 401s trigger exactly ONE refresh; late callers await the
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
// A settled Deferred is not reused, so the next wave starts a fresh refresh. A keyed wave (Nov 10, 2026) is joined
// only by callers asking about the same key: a credential renewal decided from one token is never the answer for a
// caller that holds another.
package splice.upstream.retry

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import kotlin.coroutines.CoroutineContext

/**
 * The work a wave of concurrent callers COALESCES onto — in this tree, always a credential refresh.
 *
 * Its contract is the single-flight invariant itself and nothing weaker: it runs EXACTLY ONCE per
 * wave however many callers pile up and however many of them are cancelled mid-wait, and it runs in
 * a scope [SingleFlight] owns rather than any caller's. So it must not close over one caller's
 * cancellation, one caller's deadline, or anything else that would make "whoever got there first"
 * observable in the shared result every survivor receives.
 */
public fun interface CoalescedWork<T> {
    public suspend operator fun invoke(): T
}

public class SingleFlight<T>(
    // The refresh runs here, off the caller's coroutine. Injectable for tests (a test dispatcher);
    // the background dispatcher is only the production default for a background auth refresh.
    // HD-19: the concrete Dispatchers.Default it used to name now comes from the process runtime
    // edge, so the value is unchanged and this file no longer reaches for a global dispatcher.
    context: CoroutineContext = ProcessDispatchers().background(),
) {
    // LifecycleScope applies SupervisorJob on the RIGHT of the caller's context so it unconditionally
    // wins the Job key — even if a caller passes a context that carries its own Job (e.g.
    // someScope.coroutineContext), isolation is preserved and the injected context can't tie this
    // scope's lifetime/failure-propagation to a caller's Job. That invariant moved into the type
    // (HD-19); the resulting context is identical to the old `CoroutineScope(context + SupervisorJob())`.
    private val scope = LifecycleScope(context)
    private val mutex = Mutex()
    private var inflight: Deferred<T>? = null

    /** What the wave in flight was started for (null: for anything). */
    private var inflightFor: String? = null

    /** Runs [block] once even under concurrent callers; everyone awaits the same shared result. */
    public suspend fun run(block: CoalescedWork<T>): T = run(null, block)

    /** Runs [block] once per wave for callers asking about the same [key]: a caller joins a wave in flight that was
     *  started for its key, for no key, or when it brings none. A caller with another key never takes a result decided
     *  about a value it does not hold: it waits for that wave to settle and starts its own, so a renewal asked about
     *  an older token than the caller refused cannot answer it with the very token it refused. */
    public suspend fun run(key: String?, block: CoalescedWork<T>): T {
        while (true) {
            val (shared, joined) = mutex.withLock {
                // reuse only a still-running refresh; a settled one means the next wave starts fresh.
                val running = inflight?.takeIf { it.isActive }
                when {
                    running == null -> scope.async { block() }.also {
                        inflight = it
                        inflightFor = key
                    } to true
                    key == null || inflightFor == null || key == inflightFor -> running to true
                    else -> running to false
                }
            }
            // A caller's cancellation cancels THIS await only — the shared block keeps running in `scope`.
            if (joined) return shared.await()
            shared.join()
        }
    }

    /** Cancel the shared refresh scope. For a LIFECYCLE owner (e.g. daemon stop) to stop an
     *  in-flight refresh — distinct from a single caller's await cancellation, which never touches
     *  the scope. Idempotent; after close a new wave would need a new SingleFlight. */
    public fun close() {
        scope.cancel()
    }
}
