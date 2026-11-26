// PORT-OF: splice/app/Daemon.kt (assembleHead's HeadServer + HeadDeps construction) @ ed5c868 —
// invariants unchanged: upstream, inferenceToken, forwardClientAuth, the per-head InflightGate,
// ShadowClassifier and the shared RequestMaterializationGate. `val key = ctx.key` is kept so
// kt-head-scoped-config-must-be-keyed still covers this head-scoped function.
package splice.app.head

import splice.app.AdoptedServing
import splice.app.ConsoleEventPublisher
import splice.app.HeadSentCredentials
import splice.app.auth.claude.AddedClaudeLogins
import splice.app.provider.ProviderBuild
import splice.core.budget.NoHeadBudget
import splice.core.compaction.SessionProject
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.SpliceConfig
import splice.core.config.TurnKey
import splice.core.config.UserHome
import splice.core.prompt.SystemPromptLayers
import splice.core.server.ServedTurnBoard
import splice.core.topology.ProjectConfig
import splice.core.util.LogSink
import splice.core.version.ClientVersionTracker
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.NoHeadEvents
import splice.head.OtherHeads
import splice.head.ProviderRuntime
import splice.head.admission.RequestMaterializationGate
import splice.head.compact.ShadowClassifier
import splice.head.compaction.CompactionRecordings
import splice.head.compaction.CompactionTail
import splice.head.compaction.SessionProjectLookup
import splice.head.turn.LiveTurns
import splice.head.wire.WireTap
import splice.sessions.registry.NoServedTurn
import splice.sessions.registry.ServedTurn
import splice.upstream.Provider
import splice.upstream.retry.GateObservation
import splice.upstream.retry.InflightGate
import splice.upstream.retry.ObservedInflightGate
import splice.usage.budgets.BudgetPlans
import java.nio.file.Path

internal class HeadServerFactory(
    private val config: ConfigService,
    private val mgmtKey: MgmtKey,
    private val log: LogSink,
    private val compactionTail: CompactionTail = CompactionTail(),
    private val clientVersions: ClientVersionTracker = ClientVersionTracker(),
    /** What every head's system-prompt layers resolve from (V4-160, see [HeadPromptInputs]). */
    private val prompts: HeadPromptInputs = HeadPromptInputs(),
    /** V4-134: the daemon's ONE console publisher, which every head reports through. Null only for
     *  a factory built outside the daemon (tests); Daemon passes ControlPlane's, because a
     *  head built without it would serve turns the console never hears of. */
    private val console: ConsoleEventPublisher? = null,
) {
    /** What splice ships beside Claude Code's prompt for a head that sets none. */
    private val shippedPrompts = ShippedStandingPrompts()

    private val upstreamFactory = UpstreamFactory()
    internal var performance: splice.app.observability.PerfTelemetry? = null
        set(value) {
            field = value
            upstreamFactory.performance = value
        }
    private val voices = HeadVoiceFacts()

    /** Assigned before any head is assembled; the callback reads the login owner at request time. */
    internal var credentialAccountNames: HeadDeps.CredentialAccountNames = HeadDeps.CredentialAccountNames { null }

    /** Assigned before any head is assembled, so a served headless turn becomes a session row. */
    internal var servedTurn: ServedTurn = NoServedTurn

    /** The sockets a manager handed the daemon, assigned by Daemon before it starts (see [AdoptedServing]). Like the
     *  two below it is assigned rather than a constructor parameter, which is at its width ceiling, and nothing reads
     *  it until [headServerFor] builds a head. The default binds every head port itself. */
    internal var adopted: AdoptedServing = AdoptedServing()

    /** Assigned beside [credentialAccountNames]: hears each head's sent credential digests under that head's key and
     *  the session each request named. */
    internal var sentCredentials: HeadSentCredentials = HeadSentCredentials { _, _, _ -> }

    /** The harness (docs/specs/harness.md): the capabilities splice.toml declares, assigned by Daemon before any head
     *  is built, like [adopted]. The default declares none. */
    internal var capabilities: HeadCapabilities = HeadCapabilities()

    /** door-default (docs/specs/door-default.md, decision 9): what each head hands to other heads, assigned by Daemon
     *  before any head is built, like [adopted]. The default hands nothing on. */
    internal var others: HeadOthers = HeadOthers { OtherHeads { null } }

    /** Server mode (F5): the one board every head tells how a turn the server listener relayed went, and the listener
     *  waits on. */
    internal val served: ServedTurnBoard = ServedTurnBoard()

    // v0.4.0: what a launched session holds, derived from the management key (see [TurnKey]).
    private val turnKey = TurnKey(mgmtKey)

    // Process-shared, so it is read off the GLOBAL layer (no head key) — and read at every admission, like the two
    // per-head knobs below: an operator who widens the budget for one big request is obeyed by the next one.
    private val requestMaterializationGate = RequestMaterializationGate(
        { config.getConfig().materializationHeapBytes },
        heap = splice.upstream.memory.JvmHeap.budget,
    )

    internal fun headServerFor(
        ctx: ProviderBuild,
        provider: Provider,
        stores: HeadStores,
        forwardClientAuth: Boolean,
        compactionRecordings: CompactionRecordings,
    ): HeadServer = HeadServer(
        provider = provider,
        listenPort = ctx.head.port,
        inherited = adopted.head(ctx.key),
        voiceFacts = voices.of(ctx.head.provider, ctx.providerCfg),
        forwarded = ctx.providerCfg.forwarded,
        deps = depsFor(ctx, stores, compactionRecordings, RouteScope(ctx.key, registers = true, forwardClientAuth)),
    )

    /** gateway-no-heads: the provider's runtime for the gateway, over [stores] (the provider's books), built from the
     *  same deps a head's is. [scope] says which config layer it reads and whether it registers with the console. */
    internal fun runtimeFor(
        ctx: ProviderBuild,
        provider: Provider,
        stores: HeadStores,
        compactionRecordings: CompactionRecordings,
        scope: RouteScope,
    ): ProviderRuntime = ProviderRuntime(
        provider,
        depsFor(ctx, stores, compactionRecordings, scope),
        voices.of(ctx.head.provider, ctx.providerCfg),
        ctx.providerCfg.forwarded,
    )

    /** What a route over a provider's books runs on, whoever stands in front of it. [scope] decides the two things a
     *  head and the gateway differ in: the config layer it reads, and whether it registers with the console. */
    private fun depsFor(
        ctx: ProviderBuild,
        stores: HeadStores,
        compactionRecordings: CompactionRecordings,
        scope: RouteScope,
    ): HeadDeps {
        val key = ctx.key
        val cfg = ctx.cfg
        return HeadDeps(
            // read per request, so a key replaced while the head runs is the only one it accepts from then on, and
            // the keys that replacement retired are still refused as splice's on a head that forwards the caller's
            tokens = HeadDeps.HeadTokens(
                turnKey = { turnKey.get() },
                managementKey = { mgmtKey.get() },
                retiredKey = mgmtKey.retired::holds,
            ),
            // NO DEFAULTS on these two bundles (V4-105 items 1 and 2): the NULLABILITY is the
            // feature — a head may legitimately run without economics or quota, and the tests
            // that decline them say so through ONE testFixtures builder — but a DEFAULT let a
            // caller that simply forgot get the same head as one that chose.
            stores = headStores(key, stores, cfg, compactionRecordings, scope),
            quotaBundle = HeadDeps.HeadQuota(
                quota = stores.quota,
                accountPool = stores.accounts.pool,
                accountQuotas = stores.accounts.quotas,
                // V4-133 review: this head's ledger, priced from its own catalog, from the
                // publisher's one enforcement over the daemon's budget store. Pinned by
                // BudgetWiringPinTest: without it the console's budgets are stored and ignored.
                budget = console?.budgets
                    ?.forHead(key, ctx.catalog, BudgetPlans { stores.telemetry.economics.plans(it) })
                    ?: NoHeadBudget,
                credentialAccountNames = HeadCredentialNames(key, credentialAccountNames, sentCredentials),
                sessionFallbacks = stores.accounts.fallbacks,
            ),
            seams = seams(key),
            policy = HeadDeps.HeadPolicy(
                // Per HEAD, resolved once here: its own [heads.KEY] layer plus the V4-124
                // project layers for this head. This constructor is where a missing
                // system_prompt_file or a relative project root becomes a load-time config
                // error rather than a prompt that silently never rides.
                systemPrompt = SystemPromptLayers(
                    head = ctx.head.systemPromptFor(
                        key,
                        prompts.configDir,
                        shippedPrompts.forProvider(ctx.providerCfg),
                    ),
                    projects = prompts.projects,
                    headKey = key,
                ),
                forwardClientAuth = scope.forwardClientAuth,
                mirrorReasoning = cfg.mirrorReasoning,
                progressLine = { config.getConfig(scope.configKey).progressLine },
                // These three are re-read per head at EVERY request or heartbeat, the way the inflight ceiling is: these knobs belong to
                // the operator, who may widen one for a big or slow request mid-session without a restart.
                maxRequestBytes = { config.getConfig(scope.configKey).maxRequestBytes },
                requestReadTimeoutMs = { config.getConfig(scope.configKey).requestReadTimeoutMs },
            ),
            traffic = traffic(ctx, stores, scope),
            log = log,
        )
    }

    /** The head's traffic: its watchdog, its upstream, its inflight gate and its live turns. Its own function since
     *  failure-handling slice 2 gave the head the facts its lines name, which took [headServerFor] past detekt's
     *  method-length ceiling. */
    private fun traffic(ctx: ProviderBuild, stores: HeadStores, scope: RouteScope): HeadDeps.HeadTraffic =
        HeadDeps.HeadTraffic(
            watchdog = ctx.faultPlan.liveWatchdog,
            upstream = upstreamFactory.upstreamFor(ctx, ctx.cfg, log, stores.providerHold, prompts.configDir),
            // Re-read per head on EVERY admission (still hot-resizable): the ceiling belongs to
            // the upstream ACCOUNT, not the gateway. One shared value meant a workflow fan-out
            // admitted 100 concurrent streams into a single account, 429'd, and armed the shared
            // cooldown — measured 67% turn failure at inflight=100 vs 0.3% at <=14 (perf jsonl,
            // 2026-07-24). Per-head lets a slow upstream sit low while a fast one stays high.
            gate = gateFor(ctx.key, scope),
            liveTurns = liveTurnsFor(ctx.key, scope),
            // Fix 3 (Oct 27): a turn waits for this head's list to follow the credential it is sent with (ModelRosters).
            models = HeadDeps.CurrentModels { ctx.roster.discovered.awaitCurrent(ctx.key) },
            others = others.forHead(ctx.key),
            // a turn through the door runs on the Claude accounts added to splice, never on the person's own login
            held = AddedClaudeLogins(),
        )

    /** The head's stores. Its own function since V4-216 added the compaction recordings, which took
     *  [headServerFor] past detekt's method-length ceiling. */
    private fun headStores(
        key: String,
        stores: HeadStores,
        cfg: SpliceConfig,
        compactionRecordings: CompactionRecordings,
        scope: RouteScope,
    ): HeadDeps.HeadStores = HeadDeps.HeadStores(
        usageStore = stores.usageStore,
        perfStats = stores.telemetry.perfStats,
        economicsStore = stores.telemetry.economics,
        compaction = HeadDeps.HeadCompaction(
            compactStats = stores.telemetry.compactStats,
            compactionRecordings = compactionRecordings,
        ),
        shadow = ShadowClassifier(log = log),
        clientWindows = stores.clientWindows,
        captures = HeadDeps.HeadCaptures(
            // V4-173: the tap exists only for a head whose operator named a count, read
            // off THIS head's cfg (keyed, never the global view) so one head's opt-in keeps
            // every other head's bodies unkept. V4-239: registered, or its old one taken out, in the
            // console's registry, which GET /api/heads/{head}/wire reads.
            wireTap = cfg.wireTap.takeIf { it > 0 }?.let { WireTap(it) }
                .also { tap -> if (scope.registers) console?.wires?.put(key, tap) },
            trace = stores.trace,
            // reply-stream: the console's one hub, which this head's turns tap and the live route reads.
            replies = console?.replies,
        ).let { captures -> performance?.let { captures.copy(observations = it.recorder) } ?: captures },
    )

    /** V4-319: this head's live turns, registered in the console's registry as the head is built, the
     *  way its wire tap is, so the console's live-turn routes read and stop this head's own turns. */
    private fun liveTurnsFor(key: String, scope: RouteScope): LiveTurns =
        LiveTurns().also { live -> if (scope.registers) console?.liveTurns?.put(key, live) }

    private fun gateFor(key: String, scope: RouteScope): InflightGate {
        val telemetry = performance
        val gate = if (telemetry == null) {
            InflightGate(
                { config.getConfig(scope.configKey).maxInflight },
                { config.getConfig(scope.configKey).maxQueued },
            )
        } else {
            ObservedInflightGate.create(
                { config.getConfig(scope.configKey).maxInflight },
                { config.getConfig(scope.configKey).maxQueued },
                GateObservation(telemetry.recorder, key),
            )
        }
        if (scope.registers) telemetry?.gate(key, gate)
        return gate
    }

    /** The head's shared and per-head seams. Its own function since V4-134 added the console reporter,
     *  which took [headServerFor] past detekt's method-length ceiling. */
    private fun seams(key: String): HeadDeps.HeadSeams = HeadDeps.HeadSeams(
        requestMaterializationGate = requestMaterializationGate,
        clientVersions = clientVersions,
        session = HeadDeps.SessionSeams(
            sessionProject = SessionProjectLookup { prompts.sessionProject.projectFor(it) },
            slotInstructions = console?.slots,
            compactionTail = compactionTail,
            harness = capabilities.forHead(key, log),
        ),
        reports = HeadDeps.HeadReports(
            events = console?.forHead(key) ?: NoHeadEvents,
            served = served,
            servedTurn = servedTurn,
        ),
    )
}

/** What a route over a provider's books differs in from another: [configKey] is the config layer it reads, a head's own
 *  `[heads.<key>.overrides]` for a head and the global layer (null) for the gateway, which holds no head block; and
 *  [registers] is whether it takes the console's registries for its key (live turns, wire tap, inflight gate), which
 *  hold one entry per key, so a gateway route takes them only where no head already has; and [forwardClientAuth] is
 *  whether it forwards its caller's own login rather than running on one splice holds. */
internal data class RouteScope(val configKey: String?, val registers: Boolean, val forwardClientAuth: Boolean)

/** The requests each head hands to other heads, by the head's key. */
internal fun interface HeadOthers {
    fun forHead(key: String): OtherHeads
}

/** One head's view of the daemon's credential resolver: it names through that resolver and reports each sent digest
 *  under its own head key, which the daemon-wide resolver cannot know. */
internal class HeadCredentialNames(
    private val head: String,
    private val names: HeadDeps.CredentialAccountNames,
    private val heard: HeadSentCredentials,
) : HeadDeps.CredentialAccountNames {
    override fun forCredential(key: String): String? = names.forCredential(key)

    override fun sent(key: String, session: String?) = heard.sent(head, session, key)
}

/** What a head's system-prompt layers resolve from, one bundle because the three are read together
 *  and only for the layers. V4-160 took them out of [HeadServerFactory]'s constructor, which reached
 *  seven subsystems (constructor-width allows six); the defaults are the ones it had. */
internal data class HeadPromptInputs(
    /** The topology's directory: a relative `system_prompt_file` under [splice.core.topology.HeadConfig]
     *  resolves against it, the same rule `[compaction] file =` follows (V4-36). */
    val configDir: Path = UserHome.dir().resolve(".config/splice"),
    /** V4-124: the topology's `[projects."ROOT"]` tables. Every head gets its own layers from them. */
    val projects: Map<String, ProjectConfig> = emptyMap(),
    /** One session-to-cwd resolver for every head's prompt layers. It is consulted only when a
     *  project is configured, and it keeps its own cache. Daemon passes the one built over every
     *  head's projects tree (V4-130); the default reads the vanilla tree only. */
    val sessionProject: SessionProject = SessionProject(),
)
