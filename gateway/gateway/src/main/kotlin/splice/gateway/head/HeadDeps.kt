// PORT-OF: splice/gateway/head/HeadServer.kt (HeadDeps, DEFAULT_MAX_REQUEST_BYTES,
// DEFAULT_REQUEST_READ_TIMEOUT_MS) @ 1caedd6 — invariants unchanged: the collaborator bundle a head
// is built from, and the two request-size defaults its callers name. Split out (HD-24) because it
// is a shared CONTRACT rather than a HeadServer private — TurnDriver and TurnDriveFactory take it
// whole, HeadServerFactory builds it — and because it is the single reason splice.gateway.perf,
// splice.gateway.usage, splice.core.util and splice.spi's runtime ports appeared in HeadServer.kt's
// import list at all.
package splice.gateway.head

import splice.core.model.ClientWindows
import splice.core.prompt.HeadSystemPrompt
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.core.version.ClientVersionTracker
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.perf.PerfStats
import splice.gateway.usage.EconomicsStore
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore
import splice.spi.AccountPool
import splice.spi.InflightGate
import splice.spi.ProcessTicker
import splice.spi.ProcessWaiter
import splice.spi.Ticker
import splice.spi.UpstreamClient
import splice.spi.Waiter

/** Was `HeadDeps.DEFAULT_MAX_REQUEST_BYTES` / `HeadDeps.DEFAULT_REQUEST_READ_TIMEOUT_MS`
 *  (companion consts); same names, now at file scope in the same package.
 *
 *  V4-150: `internal`, which is the module boundary stated rather than a narrowing. These are DEFAULTS
 *  for two members of the [HeadDeps.HeadPolicy] bundle and nothing outside :gateway ever named them —
 *  the only other consumer is HeadServerFailureBranchTest, in this module's own test source set, where
 *  `internal` is visible. Unlike the five declarations V4-104 had to keep, these are CONSTANTS and not
 *  types, so the inference escape does not apply: a default expression is not part of a signature, and
 *  no consumer can bind one by inference. The compiler is the judge of that and it agrees — a public
 *  value parameter defaulting to an internal const is legal Kotlin, which is exactly what makes these
 *  two burnable where the others were not. */
internal const val DEFAULT_MAX_REQUEST_BYTES: Int = 8 * 1024 * 1024

internal const val DEFAULT_REQUEST_READ_TIMEOUT_MS: Long = 30_000

/** Collaborators the head needs, bundled to keep the constructor lean. */
public data class HeadDeps(
    val upstream: UpstreamClient,
    /** Per-install bearer used by local Claude clients. Never use a source-known sentinel here. */
    val inferenceToken: String,
    val gate: InflightGate,
    /** WHAT THE HEAD STORES (V4-105 item 1): everything it writes observations into. */
    val stores: HeadStores,
    /** WHICH ACCOUNT A TURN SPENDS: the quota trackers, and the pool that decides eligibility. */
    val quotaBundle: HeadQuota,
    /** THE SUBSTITUTABLE SEAMS: exactly what a deterministic test replaces, which is why they are
     *  one bundle — a seam is one port for the same reason. */
    val seams: HeadSeams,
    /** READ-ONCE VALUES: nothing here is derived from a turn. */
    val policy: HeadPolicy,
    val compactionTail: CompactionTail = CompactionTail(),
    val log: LogSink,
) {
    /** The single resolver for which quota tracker a turn reads (V4-99): the SELECTED account's
     *  tracker, else the primary's. A body property, not a constructor param, so it is not part of
     *  the data class's equals/copy — it is a derived collaborator, not a value. */
    internal val turnQuota: TurnQuota =
        TurnQuota(quotaBundle.accountPool, quotaBundle.accountQuotas, quotaBundle.quota)

    init {
        require(inferenceToken.isNotBlank()) { "inferenceToken must not be blank" }
        require(policy.requestReadTimeoutMs > 0) { "requestReadTimeoutMs must be positive" }
        require(!policy.mirrorReasoning) { "mirrorReasoning is operator-locked off" }
    }

    /** Where the head writes what it observes. Grouped by ROLE — a bundle is a boundary, not a bag. */
    public data class HeadStores(
        val usageStore: UsageStore,
        val perfStats: PerfStats,
        val economicsStore: EconomicsStore?,
        val compactStats: CompactStats,
        val shadow: ShadowClassifier,
        val clientWindows: ClientWindows,
    )

    /** Which account a turn spends, and the trackers that decide eligibility. */
    public data class HeadQuota(
        val quota: QuotaTracker?,
        val accountPool: AccountPool?,
        val accountQuotas: Map<String, QuotaTracker>,
    )

    /** The substitutable runtime seams. A test drives these instead of sleeping or reading a clock,
     *  which is why they are one bundle: they are the things a deterministic test REPLACES. */
    public data class HeadSeams(
        val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
        val waiter: Waiter = ProcessWaiter(),
        val ticker: Ticker = ProcessTicker(),
        val requestMaterializationGate: RequestMaterializationGate = RequestMaterializationGate(),
        val clientVersions: ClientVersionTracker = ClientVersionTracker(),
    )

    /** Read-once values. Nothing here is derived from a turn, which is what makes it policy rather
     *  than state: an operator sets it and every turn reads the same answer. */
    public data class HeadPolicy(
        val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
        val requestReadTimeoutMs: Long = DEFAULT_REQUEST_READ_TIMEOUT_MS,
        val mirrorReasoning: Boolean = false,
        val progressLine: Boolean = true,
        val forwardClientAuth: Boolean = false,
        val systemPrompt: HeadSystemPrompt = HeadSystemPrompt(),
    )
}
