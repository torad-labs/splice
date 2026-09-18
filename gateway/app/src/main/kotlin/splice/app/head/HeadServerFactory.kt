// PORT-OF: splice/app/Daemon.kt (assembleHead's HeadServer + HeadDeps construction) @ ed5c868 —
// invariants unchanged: upstream, inferenceToken, forwardClientAuth, the per-head InflightGate,
// ShadowClassifier and the shared RequestMaterializationGate. `val key = ctx.key` is kept so
// kt-head-scoped-config-must-be-keyed still covers this head-scoped function.
package splice.app.head

import splice.app.ConsoleEventPublisher
import splice.app.provider.ProviderBuild
import splice.core.compaction.SessionProject
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.prompt.SystemPromptLayers
import splice.core.topology.ProjectConfig
import splice.core.util.LogSink
import splice.core.version.ClientVersionTracker
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.CompactionTail
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.head.NoHeadEvents
import splice.gateway.head.RequestMaterializationGate
import splice.gateway.head.SessionProjectLookup
import splice.spi.InflightGate
import splice.spi.Provider
import java.nio.file.Path
import java.nio.file.Paths

internal class HeadServerFactory(
    private val config: ConfigService,
    private val mgmtKey: MgmtKey,
    private val log: LogSink,
    private val compactionTail: CompactionTail = CompactionTail(),
    private val clientVersions: ClientVersionTracker = ClientVersionTracker(),
    /** The topology's directory: a relative `system_prompt_file` under [splice.core.topology.HeadConfig]
     *  resolves against it, the same rule `[compaction] file =` follows (V4-36). */
    private val configDir: Path = Paths.get(System.getProperty("user.home"), ".config", "splice"),
    /** V4-124: the topology's `[projects."ROOT"]` tables. Every head gets its own layers from them. */
    private val projects: Map<String, ProjectConfig> = emptyMap(),
    /** V4-134: the daemon's ONE console publisher, which every head reports through. Null only for
     *  a factory built outside the daemon (tests); Daemon passes ControlPlane's, pinned by
     *  OneEventBusPinTest, because a head built without it would serve turns the console never hears. */
    private val console: ConsoleEventPublisher? = null,
    /** One session-to-cwd resolver for every head's prompt layers. It is consulted only when a
     *  project is configured, and it keeps its own cache. Daemon passes the one built over every
     *  head's projects tree (V4-130); the default reads the vanilla tree only. */
    private val sessionProject: SessionProject = SessionProject(),
) {
    private val upstreamFactory = UpstreamFactory()

    private val requestMaterializationGate = RequestMaterializationGate(materializationPermits())

    internal fun headServerFor(
        ctx: ProviderBuild,
        provider: Provider,
        stores: HeadStores,
        forwardClientAuth: Boolean,
    ): HeadServer {
        val key = ctx.key
        val cfg = ctx.cfg
        val knobs = cfg.asMap()
        return HeadServer(
            provider = provider,
            listenPort = ctx.head.port,
            deps = HeadDeps(
                upstream = upstreamFactory.upstreamFor(ctx, cfg, log),
                inferenceToken = mgmtKey.get(),
                // NO DEFAULTS on these two bundles (V4-105 items 1 and 2): the NULLABILITY is the
                // feature — a head may legitimately run without economics or quota, and the tests
                // that decline them say so through ONE testFixtures builder — but a DEFAULT let a
                // caller that simply forgot get the same head as one that chose.
                stores = HeadDeps.HeadStores(
                    usageStore = stores.usageStore,
                    perfStats = stores.perfStats,
                    economicsStore = stores.economics,
                    compactStats = stores.compactStats,
                    shadow = ShadowClassifier(log = log),
                    clientWindows = stores.clientWindows,
                ),
                quotaBundle = HeadDeps.HeadQuota(
                    quota = stores.quota,
                    accountPool = stores.accountPool,
                    accountQuotas = stores.accountQuotas,
                ),
                seams = seams(key),
                policy = HeadDeps.HeadPolicy(
                    // Per HEAD, resolved once here: its own [heads.KEY] layer plus the V4-124
                    // project layers for this head. This constructor is where a missing
                    // system_prompt_file or a relative project root becomes a load-time config
                    // error rather than a prompt that silently never rides.
                    systemPrompt = SystemPromptLayers(
                        head = ctx.head.systemPromptFor(key, configDir),
                        projects = projects,
                        headKey = key,
                    ),
                    forwardClientAuth = forwardClientAuth,
                    mirrorReasoning = cfg.mirrorReasoning,
                    progressLine = cfg.progressLine,
                    maxRequestBytes = (knobs[Knob.MAX_REQUEST_BYTES.key] as Long).toInt(),
                    requestReadTimeoutMs = knobs[Knob.REQUEST_READ_TIMEOUT_MS.key] as Long,
                ),
                // Re-read per head on EVERY admission (still hot-resizable): the ceiling belongs to
                // the upstream ACCOUNT, not the gateway. One shared value meant a workflow fan-out
                // admitted 100 concurrent streams into a single account, 429'd, and armed the shared
                // cooldown — measured 67% turn failure at inflight=100 vs 0.3% at <=14 (perf jsonl,
                // 2026-07-24). Per-head lets a slow upstream sit low while a fast one stays high.
                gate = InflightGate(
                    maxInflight = { config.getConfig(key).maxInflight },
                    maxQueued = { config.getConfig(key).maxQueued },
                ),
                compactionTail = compactionTail,
                log = log,
            ),
        )
    }

    /** The head's shared and per-head seams. Its own function since V4-134 added the console reporter,
     *  which took [headServerFor] past detekt's method-length ceiling. */
    private fun seams(key: String): HeadDeps.HeadSeams = HeadDeps.HeadSeams(
        requestMaterializationGate = requestMaterializationGate,
        clientVersions = clientVersions,
        sessionProject = SessionProjectLookup { sessionProject.projectFor(it) },
        events = console?.forHead(key) ?: NoHeadEvents,
    )

    /** V4-110: the process-shared materialization permit count, read from the GLOBAL knob layer (no
     *  head key) once at daemon boot. One value bounds every head's concurrent decode/translate. */
    private fun materializationPermits(): Int {
        val m = config.getConfig().asMap()
        return (m[Knob.MATERIALIZATION_PERMITS.key] as Long).toInt()
    }
}
