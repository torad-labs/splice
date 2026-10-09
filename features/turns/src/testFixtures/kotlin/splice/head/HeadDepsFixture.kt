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
package splice.head

import splice.core.budget.HeadBudget
import splice.core.budget.NoHeadBudget
import splice.core.model.ClientWindows
import splice.core.util.LogSink
import splice.head.compact.CompactStats
import splice.head.compact.ShadowClassifier
import splice.head.compaction.FileCompactionRecordings
import splice.head.perf.PerfStats
import splice.head.turn.LiveTurns
import splice.head.usage.EconomicsStore
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.head.wire.TraceStore
import splice.head.wire.WireTap
import splice.upstream.credentials.AccountPool
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path

/** A head's stored observations, all of them pointed at [tmp].
 *
 *  [suffix] exists for the files that build MORE THAN ONE rig: two heads in one test must not share
 *  a usage file, or the second rig reads the first's rows and the test passes for the wrong reason.
 *  The site that needs it says so explicitly rather than the fixture guessing. */
public fun headStores(
    tmp: Path,
    economics: EconomicsStore? = null,
    suffix: String = "",
    /** V4-173: off unless a cell turns it on — the production default, not a convenience. */
    wireTap: WireTap? = null,
    trace: TraceStore? = null,
): HeadDeps.HeadStores = HeadDeps.HeadStores(
    usageStore = UsageStore(tmp.resolve("usage$suffix.json"), tmp.resolve("ratelimit$suffix.json")),
    perfStats = PerfStats(tmp.resolve("perf$suffix.jsonl")),
    economicsStore = economics,
    compactStats = CompactStats(tmp.resolve("compact$suffix.jsonl")),
    shadow = ShadowClassifier(log = { }),
    clientWindows = ClientWindows(),
    wireTap = wireTap,
    trace = trace,
    compactionRecordings = FileCompactionRecordings(tmp.resolve("compactions$suffix"), log = { }),
)

/** No quota, no pool and no budget: the shape a head that neither observes nor emits quota runs as. */
public fun noQuota(): HeadDeps.HeadQuota =
    HeadDeps.HeadQuota(null, null, emptyMap(), NoHeadBudget, HeadDeps.CredentialAccountNames { null })

/** A quota bundle for a head that DOES have a pool, so a test can name the three together, or a
 *  [budget] (V4-133 review) for a head whose turns a test wants weighed against one. */
public fun quotaFor(
    quota: QuotaTracker?,
    pool: AccountPool?,
    quotas: Map<String, QuotaTracker> = emptyMap(),
    budget: HeadBudget = NoHeadBudget,
): HeadDeps.HeadQuota = HeadDeps.HeadQuota(quota, pool, quotas, budget, HeadDeps.CredentialAccountNames { null })

/**
 * Head deps for a test. Every parameter is defaulted so a site names only what it overrides.
 *
 * FIVE PARAMETERS IS THE CEILING (a function is flagged at six, defaults counted), and it forced real
 * choices: `stores`, `quota` and `policy` are NOT parameters. Each is a fixed default (`headStores(tmp)`,
 * [noQuota], an empty [HeadDeps.HeadPolicy]) because most sites want exactly that, and the sites that do not
 * pass `.copy(stores = headStores(tmp, economics = store))`, `.copy(quotaBundle = quotaFor(...))` or
 * `.copy(policy = ...)` — HeadDeps is a data class, so composition costs one clause at the rare site instead
 * of a parameter at every one. The five that ARE here are the ones the tree overrides most: a custom
 * upstream, a log sink, a gate, and the seams.
 */
public fun headDeps(
    tmp: Path,
    upstream: UpstreamClient = UpstreamClient(totalTimeoutMs = 30_000L, maxRetries = 2),
    log: LogSink = { },
    gate: InflightGate = InflightGate({ 0 }),
    seams: HeadDeps.HeadSeams = HeadDeps.HeadSeams(),
    policy: HeadDeps.HeadPolicy = HeadDeps.HeadPolicy(),
): HeadDeps = HeadDeps(
    upstream = upstream,
    inferenceToken = "test-inference-token",
    operatorToken = "test-operator-token",
    gate = gate,
    // V4-319: a head's own registry, as a head nobody lists turns of is built; a test that stops a turn
    // passes its own with `.copy(liveTurns = …)`, the way the rare economics site passes its stores.
    liveTurns = LiveTurns(),
    log = log,
    stores = headStores(tmp),
    quotaBundle = noQuota(),
    policy = policy,
    seams = seams,
)
