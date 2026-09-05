// PORT-OF: splice/gateway/head/HeadServer.kt (HeadDeps, DEFAULT_MAX_REQUEST_BYTES,
// DEFAULT_REQUEST_READ_TIMEOUT_MS) @ 1caedd6 — invariants unchanged: the collaborator bundle a head
// is built from, and the two request-size defaults its callers name. Split out (HD-24) because it
// is a shared CONTRACT rather than a HeadServer private — TurnDriver and TurnDriveFactory take it
// whole, HeadServerFactory builds it — and because it is the single reason splice.gateway.perf,
// splice.gateway.usage, splice.core.util and splice.spi's runtime ports appeared in HeadServer.kt's
// import list at all.
package splice.gateway.head

import splice.core.model.ClientWindows
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.perf.PerfStats
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore
import splice.spi.InflightGate
import splice.spi.ProcessTicker
import splice.spi.ProcessWaiter
import splice.spi.Ticker
import splice.spi.UpstreamClient
import splice.spi.Waiter

/** Was `HeadDeps.DEFAULT_MAX_REQUEST_BYTES` / `HeadDeps.DEFAULT_REQUEST_READ_TIMEOUT_MS`
 *  (companion consts); same names, now at file scope in the same package. */
public const val DEFAULT_MAX_REQUEST_BYTES: Int = 8 * 1024 * 1024

public const val DEFAULT_REQUEST_READ_TIMEOUT_MS: Long = 30_000

/** Collaborators the head needs, bundled to keep the constructor lean. */
public data class HeadDeps(
    val upstream: UpstreamClient,
    /** Per-install bearer used by local Claude clients. Never use a source-known sentinel here. */
    val inferenceToken: String,
    val gate: InflightGate,
    val shadow: ShadowClassifier,
    val compactStats: CompactStats,
    val usageStore: UsageStore,
    val perfStats: PerfStats,
    /** The head's quota windows: fed by upstream rounds and the app-side poller, stamped onto
     *  every client response as the unified rate-limit headers Claude Code draws its bars from.
     *  Null = a head that neither observes nor emits them (tests, and nothing else). */
    val quota: QuotaTracker? = null,
    /** The window each Claude Code session actually runs with, learned from its status-line posts
     *  (the control plane records; the usage payload reads). One per head, shared with ManagedHead. */
    val clientWindows: ClientWindows = ClientWindows(),
    val log: LogSink,
    val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    /** HD-19: the head's two runtime seams, defaulted to the exact behaviour they replaced.
     *  [waiter] paces HeadServer's bounded stop-drain poll; [ticker] paces TurnDriver's client
     *  keepalive pinger. Both are named ports rather than a bare `delay`, so a head test can drive
     *  a drain or a ping cadence deterministically instead of sleeping through it. */
    val waiter: Waiter = ProcessWaiter(),
    val ticker: Ticker = ProcessTicker(),
    val requestMaterializationGate: RequestMaterializationGate = RequestMaterializationGate(),
    val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
    val requestReadTimeoutMs: Long = DEFAULT_REQUEST_READ_TIMEOUT_MS,
    // Operator-locked off: provider-native reasoning may display, but splice never mirrors it.
    val mirrorReasoning: Boolean = false,
    /** TRUE only for a head whose auth kind is `client` (campaign claude-head): splice holds no
     *  credential for it, so the caller's own auth headers are forwarded upstream and the
     *  mgmt-key front door is bypassed. FALSE for every other head, which keeps enforcing it. */
    val forwardClientAuth: Boolean = false,
) {
    init {
        require(inferenceToken.isNotBlank()) { "inferenceToken must not be blank" }
        require(requestReadTimeoutMs > 0) { "requestReadTimeoutMs must be positive" }
        require(!mirrorReasoning) { "mirrorReasoning is operator-locked off" }
    }
}
