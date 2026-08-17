// NEW: the gateway's named runtime seams (HD-19). Production logic used to reach for the global
// clock (`delay`), the global dispatchers (`Dispatchers.IO/Default`) and unbound `CoroutineScope()`
// factories directly. Three costs were measured, not assumed: retry/backoff could not be asserted
// without wall-clock waiting (the HeadServerFoldTest NF-03 flake class), a second transport that
// could not fit the un-named shape went around it entirely (the WS runner bypasses UpstreamClient,
// so `attempts` is present on 4% of one head's perf rows against 99% of another's), and nothing
// mechanical stopped the next site from doing the same.
//
// These are `fun interface`, deliberately. It is the same tenet as no-lambda-seam: this repo has
// 246 raw function-type seams against 6 named ones, and every port named here is a down payment on
// that wave. A named port also gives the test a name to wire — `RecordingWaiter` reads as intent
// where `{ _ -> }` reads as noise.
//
// The PRODUCTION adapters live in ProcessRuntime.kt, which is the single main-source file the three
// HD-19 walls exempt. Nothing else in gateway/*/src/main may name delay or Dispatchers again.
package splice.spi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * A one-shot wait. The seam behind every backoff, poll interval and retry pause on the turn path.
 *
 * Production wires [ProcessWaiter], which is `delay(ms)` and nothing else. A test wires a recorder
 * that returns instantly and captures the requested intervals, which is what turns "three retries
 * at 1s, 2s, 4s" from a four-second sleep into an assertion on a list.
 */
public fun interface Waiter {
    public suspend fun wait(ms: Long)
}

/**
 * The pacing seam for an unbounded loop — the `while (isActive) { work(); delay(interval) }` shape.
 *
 * Distinct from [Waiter] by its RETURN: false means no further tick will come, so the loop exits
 * cleanly. That is not decoration. A fake that could only stop the loop by throwing would trip
 * TurnPathProbeLoop.supervise, whose whole job is to raise the stall alarm on a non-cancellation
 * completion — the test would be asserting on an alarm it caused itself. [ProcessTicker] always
 * returns true, so production loops are exactly as unbounded as they were.
 */
public fun interface Ticker {
    /** Suspends for [intervalMs]. Returns false when the caller's loop should stop. */
    public suspend fun awaitTick(intervalMs: Long): Boolean
}

/**
 * A scope with a NAMED owner, which is the whole difference between this and a bare
 * `CoroutineScope(ctx)`: the factory produces a scope whose lifetime nobody is responsible for,
 * while an instance of this type belongs to whoever holds the field (Daemon holds the probe scope,
 * SingleFlight holds the shared-refresh scope) and dies when that owner cancels it.
 *
 * SupervisorJob is applied HERE rather than at each call site, and is applied on the RIGHT of the
 * caller's [context] so it unconditionally wins the Job key — a caller that passes a context
 * carrying its own Job (e.g. `someScope.coroutineContext`) still gets an isolated scope whose
 * failures do not propagate to that caller. That was already SingleFlight's invariant, spelled the
 * long way; it is now the type's.
 *
 * Cancellation is the standard `CoroutineScope.cancel()` extension — no new method, no new
 * lifecycle vocabulary.
 */
public class LifecycleScope(context: CoroutineContext) : CoroutineScope {
    override val coroutineContext: CoroutineContext = context + SupervisorJob()
}

/**
 * The two runtime seams a bounded poll needs, as ONE cohesive argument: what it waits with, and
 * which dispatcher it waits on. Grouped for the same reason UpstreamClient.PostContext is —
 * threading them separately pushes CredentialLock.withFileLock past detekt's parameter budget, and
 * they are never independently interesting: a caller that replaces one always replaces both.
 *
 * The defaults are the production runtime, so every existing call site is unchanged.
 */
public data class PollRuntime(
    val waiter: Waiter = ProcessWaiter(),
    val dispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
)
