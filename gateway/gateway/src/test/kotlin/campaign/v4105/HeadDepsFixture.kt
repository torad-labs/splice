// NEW: V4-105 — the ONE test-fixtures head-deps builder the ruling asked for.
//
// WHY IT EXISTS, beyond convenience: HeadStores and HeadQuota deliberately carry NO defaults, so
// after item 1 every construction site must name them. That is the right shape for PRODUCTION — a
// site that forgets cannot silently inherit a null tracker — but in test code it would have been
// twenty-six copies of the same six-argument bag, which is the copy-drift shape this campaign keeps
// finding. So the bag is written once here and a test names only what it actually overrides.
//
// SEVEN PARAMETERS, and that is a wall constraint rather than a style choice: a function is not
// exempt from the parameter-count wall the way a data class is, and a spec data class holding all
// twenty-five flat fields would have been a NEW wide primary constructor — trading twenty-six test
// call sites for a fresh ratchet offender. Taking the bundles keeps this at seven.
//
// `tmp` is a parameter rather than a directory made here on purpose: a test that writes stores must
// control where they land, or the fixture itself becomes the thing leaking files between tests.
package campaign.v4105

import splice.core.model.ClientWindows
import splice.core.util.LogSink
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.perf.PerfStats
import splice.gateway.usage.EconomicsStore
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore
import splice.spi.AccountPool
import splice.spi.InflightGate
import splice.spi.UpstreamClient
import java.nio.file.Path

/** A head's stored observations, all of them pointed at [tmp].
 *
 *  [suffix] exists for the files that build MORE THAN ONE rig: two heads in one test must not share
 *  a usage file, or the second rig reads the first's rows and the test passes for the wrong reason.
 *  The site that needs it says so explicitly rather than the fixture guessing. */
public fun headStores(
    tmp: Path,
    economics: EconomicsStore? = null,
    clientWindows: ClientWindows = ClientWindows(),
    suffix: String = "",
): HeadDeps.HeadStores = HeadDeps.HeadStores(
    usageStore = UsageStore(tmp.resolve("usage$suffix.json"), tmp.resolve("ratelimit$suffix.json")),
    perfStats = PerfStats(tmp.resolve("perf$suffix.jsonl")),
    economicsStore = economics,
    compactStats = CompactStats(tmp.resolve("compact$suffix.jsonl")),
    shadow = ShadowClassifier(log = { }),
    clientWindows = clientWindows,
)

/** No quota and no pool: the shape a head that neither observes nor emits quota runs as. */
public fun noQuota(): HeadDeps.HeadQuota = HeadDeps.HeadQuota(null, null, emptyMap())

/** A quota bundle for a head that DOES have a pool, so a test can name the three together. */
public fun quotaFor(
    quota: QuotaTracker?,
    pool: AccountPool?,
    quotas: Map<String, QuotaTracker> = emptyMap(),
): HeadDeps.HeadQuota = HeadDeps.HeadQuota(quota, pool, quotas)

/**
 * Head deps for a test. Every parameter is defaulted so a site names only what it overrides.
 *
 * SEVEN PARAMETERS IS THE CEILING, and it forced one real choice: `stores` is NOT a parameter. It is
 * always [headStores]`(tmp)`, because that is what all but a couple of sites want, and the couple
 * that want economics pass `.copy(stores = headStores(tmp, economics = store))` — HeadDeps is a data
 * class, so composition costs one clause at the rare site instead of a parameter at every one. The
 * seven that ARE here are the ones the tree actually overrides: a custom gate (26 sites), a custom
 * upstream timeout, a log sink, a real pool, a policy tweak, and the seams.
 */
public fun headDeps(
    tmp: Path,
    upstream: UpstreamClient = UpstreamClient(firstByteTimeoutMs = 5_000L, totalTimeoutMs = 30_000L, maxRetries = 2),
    log: LogSink = { },
    gate: InflightGate = InflightGate({ 0 }),
    quota: HeadDeps.HeadQuota = noQuota(),
    policy: HeadDeps.HeadPolicy = HeadDeps.HeadPolicy(),
    seams: HeadDeps.HeadSeams = HeadDeps.HeadSeams(),
): HeadDeps = HeadDeps(
    upstream = upstream,
    inferenceToken = "test-inference-token",
    gate = gate,
    log = log,
    stores = headStores(tmp),
    quotaBundle = quota,
    policy = policy,
    seams = seams,
)
