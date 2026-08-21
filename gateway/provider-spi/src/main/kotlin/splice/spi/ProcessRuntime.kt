// NEW: the process runtime edge (HD-19) — the ONE main-source file that may name the global clock
// and the global dispatchers. Everything else in gateway/*/src/main receives a [Waiter], a [Ticker]
// or a CoroutineDispatcher through its constructor and cannot reach the runtime on its own; the
// three HD-19 walls (kt-no-delay-in-production, kt-main-no-hardcoded-dispatchers,
// kt-no-coroutine-scope-factory) name this exact path in their `ignores:` and nothing else.
//
// Why an exempt file exists at all, stated plainly rather than hidden in a glob: a dependency-
// injected process must name its concrete runtime SOMEWHERE, and the honest choice is one file that
// does nothing else, so the exemption can never grow to cover logic. The precedent in this repo is
// kt-no-system-getenv (`ignores: core/**/config/**`) and kt-no-println's inline AsyncFileIo
// carve-out: convert every site, exempt exactly the edge, say so in the header.
//
// The three types are stateless and their constructors are EMPTY — the daemon's carry-forward risk
// about "constructed collaborator per invocation" being safe only while collaborator constructors
// do no work applies here, and is satisfied by construction.
package splice.spi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import splice.core.util.MonoClock

/** The production [ElapsedNow]: [MonoClock.nowMs] and nothing else. */
public class ProcessElapsedNow : ElapsedNow {
    override fun invoke(): Long = MonoClock.nowMs()
}

/** The production [Waiter]: `delay` and nothing else. */
public class ProcessWaiter : Waiter {
    override suspend fun wait(ms: Long) {
        delay(ms)
    }
}

/** The production [Ticker]: `delay`, then always continue — so a wired loop is exactly as
 *  unbounded as the `while (isActive) { ...; delay(interval) }` it replaced. */
public class ProcessTicker : Ticker {
    override suspend fun awaitTick(intervalMs: Long): Boolean {
        delay(intervalMs)
        return true
    }
}

/** The dispatchers the production process runs on, each returning the exact value its call sites
 *  hardcoded before HD-19 — `io()` for blocking JVM I/O (Netty engine stops, HttpURLConnection
 *  probes, FileChannel lock polling) and `background()` for owned background scopes (auth prefetch,
 *  the per-head probe scope). No behaviour is chosen here that was not already chosen. */
public class ProcessDispatchers {
    public fun io(): CoroutineDispatcher = Dispatchers.IO

    public fun background(): CoroutineDispatcher = Dispatchers.Default
}
