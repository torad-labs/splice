// PORT-OF: splice/app/Daemon.kt (assembleHead) @ ed5c868 — invariants unchanged: the outer shell
// of head assembly — builds the three stores, calls the provider dispatch and the two factories
// below, computes apiKeyPresent and forwardClientAuth side by side (both are "what shape is this
// credential" reads of the SAME wired.auth), and assembles the ManagedHead record.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import splice.accounts.order.ACCOUNT_ORDER_FILE
import splice.accounts.order.ACCOUNT_PINS_FILE
import splice.accounts.order.AccountOrderStore
import splice.accounts.order.AccountPinStore
import splice.accounts.pool.HeadAccountPoolSource
import splice.app.control.HeadAuthSurface
import splice.app.control.HeadSources
import splice.app.control.ManagedHead
import splice.app.control.StatuslineContext
import splice.app.control.UsageWarning
import splice.app.control.UsageWarningSource
import splice.app.head.books.AssembledRoute
import splice.app.head.books.BooksQuota
import splice.app.head.books.ProviderBooks
import splice.app.head.books.SharedHoldMigration
import splice.app.head.books.SharedPool
import splice.app.head.books.SharedPools
import splice.app.probe.PlaygroundProviders
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.Wired
import splice.app.sources.CompactStatsSource
import splice.app.sources.EconomicsStoreSource
import splice.app.sources.PerfStatsSource
import splice.app.sources.UsageStoreSource
import splice.core.auth.ClientAuthProvider
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.model.ClientWindows
import splice.core.model.TurnPrice
import splice.core.perf.KeptHistory
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.DialectWires
import splice.core.usage.QuotaReadFailures
import splice.core.util.LogSink
import splice.diagnostics.logs.LogFileSource
import splice.head.ProviderRuntime
import splice.head.compact.CompactStats
import splice.head.compaction.FileCompactionRecordings
import splice.head.compaction.KeepingRecordings
import splice.head.perf.PerfStats
import splice.head.perf.SessionTotals
import splice.head.turn.fallback.SessionFallbacks
import splice.head.usage.AccountOnPlan
import splice.head.usage.CredentialQuotaFiles
import splice.head.usage.EconomicsStore
import splice.head.usage.QuotaAccountFiles
import splice.head.usage.QuotaHistory
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.oauth.AuthHttpClientFactory
import splice.provider.codex.CodexQuotaHeaderFamily
import splice.provider.openai.ApiKeyAuthProvider
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.credentials.AccountEvidenceLoads
import splice.usage.AccountPlanLookup
import splice.usage.HeadQuotaPlan
import splice.usage.UsageBilling
import splice.usage.quota.ClientUserAgent
import splice.usage.quota.QuotaCadence
import splice.usage.quota.QuotaIntervalMs
import splice.usage.quota.QuotaPoller
import splice.usage.quota.QuotaProbe
import splice.usage.quota.QuotaProbes
import splice.usage.quota.QuotaSnapshotSink

internal fun interface StartQuotaPoller {
    operator fun invoke(
        head: String,
        probe: QuotaProbe,
        tracker: QuotaTracker,
        intervalMs: QuotaIntervalMs,
    ): QuotaPoller?
}

// why: the name a head's own sign-in goes by wherever a reading names its login (QuotaAccount.label), for the
// history line of a head that wired no account of its own.
private const val HISTORY_OWN_LOGIN = "primary"

/** Observes the primary quota tracker at assembly so a test can see which tracker was wired.
 *  The production body is a no-op and nothing in production consumes the callback — unlike
 *  [StartQuotaPoller], whose default starts a real [QuotaPoller]. */
internal fun interface OnPrimaryQuota {
    operator fun invoke(tracker: QuotaTracker)
}

/** What a head's quota polling is wired with: the poller starter, the primary-tracker observer and the Claude Code
 *  User-Agent the probes present, which is the one this daemon's client was seen sending (null until a client
 *  has sent a turn, and a Claude head's probe then sends none). [probeScope] is the daemon's OWN scope, the same
 *  instance ProviderAssembly prefetches on, so the pollers end with Daemon.stop() like every other background probe. */
internal class QuotaPollSeams(
    private val probeScope: CoroutineScope,
    log: LogSink,
    private val startQuotaPoller: StartQuotaPoller = StartQuotaPoller { head, probe, tracker, intervalMs ->
        QuotaPoller(
            probeScope,
            head,
            probe,
            QuotaSnapshotSink(tracker::record),
            log,
            cadence = QuotaCadence(intervalMs = intervalMs),
        )
            .also { it.start() }
    },
    private val onPrimaryQuota: OnPrimaryQuota = OnPrimaryQuota { _ -> },
    private val clientUserAgent: ClientUserAgent = ClientUserAgent { null },
    /** The daemon's record of failed limits reads, which /api/accounts reads beside each login. */
    private val readFailures: QuotaReadFailures = QuotaReadFailures(),
) {
    fun primaryWired(tracker: QuotaTracker) {
        onPrimaryQuota(tracker)
    }

    /** Account evidence shares the daemon-owned background lifetime, not an individual request. */
    private val evidenceReaders = java.util.concurrent.ConcurrentLinkedQueue<AccountEvidenceLoads>()

    fun evidenceLoads(): AccountEvidenceLoads = AccountEvidenceLoads(
        probeScope,
        ProcessDispatchers().io(),
        heap = splice.upstream.memory.JvmHeap.budget,
    ).also(evidenceReaders::add)

    /** Permanent daemon retirement, never a reversible stop of a reusable managed head. */
    fun closeEvidenceLoads() {
        while (true) {
            val reader: AccountEvidenceLoads = evidenceReaders.poll() ?: return
            reader.close()
        }
    }

    fun pollingFor(
        ctx: ProviderBuild,
        probes: QuotaProbes,
        orders: AccountOrderStore,
        sharedPools: SharedPools? = null,
    ): HeadQuotaPolling =
        HeadQuotaPolling(ctx, probes, startQuotaPoller, clientUserAgent, orders, readFailures, sharedPools)
}

/** The head server factory and the Playground registry a head is bound into once it is built. */
internal class HeadServing(
    private val headServerFactory: HeadServerFactory,
    /** V4-444: where each assembled head's provider is registered for the Playground's one call. */
    private val playgroundProviders: PlaygroundProviders = PlaygroundProviders(),
) {
    /** Assembly binds independent replies to this head before the head is exposed to the control plane. */
    fun observedHead(
        ctx: ProviderBuild,
        wired: Wired,
        stores: HeadStores,
        forwardClientAuth: Boolean,
        recordings: FileCompactionRecordings,
    ) = headServerFactory.headServerFor(ctx, wired.provider, stores, forwardClientAuth, recordings)
        .also { playgroundProviders.bind(ctx.key, wired, stores.accounts.pool, it.providerReplies, it.compareSends) }

    /** gateway-no-heads: [ctx]'s provider as the gateway serves it, over [books]' stores. It reads the global config layer
     *  (it holds no head block) and registers with the console's per-key registries only where [registers] says no head
     *  already has. It is not bound into the Playground, which is a head's. */
    fun runtimeFor(ctx: ProviderBuild, wired: Wired, books: ProviderBooks, scope: RouteScope): ProviderRuntime =
        headServerFactory.runtimeFor(ctx, wired.provider, books.stores, books.recordings, scope)
}

internal class ManagedHeadFactory(
    private val statePaths: StatePaths,
    private val providerAssembly: ProviderAssembly,
    private val serving: HeadServing,
    private val launchSpecFactory: LaunchSpecFactory,
    private val log: LogSink,
    private val quotaSeams: QuotaPollSeams,
    /** The head's turn history: the rows every store folds from, and the window they are kept
     *  under, which [splice.app.sources.PerfSourceFiles] reads live. */
    private val perfSources: splice.app.sources.PerfSourceFiles = splice.app.sources.PerfSourceFiles(statePaths),
) {
    internal var quotaProbes: QuotaProbes = QuotaProbes(AuthHttpClientFactory().create())

    /** The model each session named to fall back to while the one it asks for is held on every login: the daemon's one
     *  store, read at each turn. Unassigned, no session has one. */
    internal var sessionFallbacks: SessionFallbacks = SessionFallbacks { null }
    private val accountPools = HeadAccountPools(HeadAccountPools.EvidenceLoadFactory(quotaSeams::evidenceLoads))
    private val accountOrders = AccountOrderStore(statePaths.stateDir.resolve(ACCOUNT_ORDER_FILE))
    private val accountPins = AccountPinStore(statePaths.stateDir.resolve(ACCOUNT_PINS_FILE))
    private val providerHolds = ProviderHoldFiles(statePaths, log)
    private val traceStores = HeadTraceStores(statePaths)
    private val keptFiles = HeadKeptFiles(statePaths, log)

    /** Pool evidence owns only the daemon lifetime, and is settled after every head has drained. */
    fun closeEvidenceLoads() = quotaSeams.closeEvidenceLoads()

    /** The books each persisted key accounts into, built for the first route that asks and the same books for every
     *  other: a legacy head and the gateway's runtime for the same provider are two routes over one set (ProviderBooks). */
    private val books = java.util.concurrent.ConcurrentHashMap<String, ProviderBooks>()

    /** Shared live account pools, quota trackers, holds, cooldowns and pollers keyed by provider + credential owners,
     *  so a legacy head and the gateway's provider runtime over the same accounts share one live pool while keeping
     *  their per-key usage/perf/economics books separate. */
    private val sharedPools = SharedPools(SharedHoldMigration(providerHolds))

    /** The keys whose kept files have been swept this daemon life, once each, before the first provider is built. */
    private val swept = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The books of [ctx]'s persisted identity. A key asked for under another provider than the one its books belong to
     *  is another identity, and answering it with these books would write two providers' hours into one file. */
    internal fun booksOf(ctx: ProviderBuild, wired: Wired): ProviderBooks {
        val found = books.computeIfAbsent(ctx.key) { buildBooks(ctx, wired) }
        check(found.provider == ctx.head.provider) {
            "the books of ${ctx.key} belong to ${found.provider}, not ${ctx.head.provider}"
        }
        return found
    }

    /** The books [ctx]'s first route builds: the quota trackers (one per account, and the primary), the stores that read
     *  them, the pollers that fill them and the kept compaction answers. */
    private fun buildBooks(ctx: ProviderBuild, wired: Wired): ProviderBooks {
        val poolKey = sharedPools.keyFor(ctx, wired, providerAssembly.claudeCanonicalKey(ctx.key))
        var firstStores: HeadStores? = null
        val shared = if (poolKey != null) {
            val (found, created) = sharedPools.resolve(poolKey) {
                buildSharedPool(ctx, wired).also { firstStores = it.first }.second
            }
            if (!created) sharedPools.attach(found, ctx.key)
            found
        } else {
            buildSharedPool(ctx, wired).also { firstStores = it.first }.second
        }
        quotaSeams.primaryWired(shared.primaryQuota)
        val stores = firstStores ?: headStoresOf(ctx, shared)
        return ProviderBooks(
            ctx.head.provider,
            stores,
            BooksQuota(shared.primaryQuota, shared.accountQuotas, shared.pollers, shared.polling),
            recordings(ctx),
        )
    }

    private fun buildSharedPool(ctx: ProviderBuild, wired: Wired): Pair<HeadStores, SharedPool> {
        val key = ctx.key
        val accountQuotas = accountQuotas(key, wired)
        val primaryQuota = wired.accounts.singleOrNull { it.primary && it.nativePlace == null }
            ?.let { accountQuotas.getValue(it.label) }
            ?: ownQuota(key)
        val pool = accountPools.build(wired, accountQuotas, providerHolds.forPool(key, wired), primaryQuota)
        sharedPools.syncInitialMembers(key, wired, pool, accountQuotas)
        if (pool != null) {
            val labels = pool.effectiveOrder().toSet()
            pool.order = accountOrders.order(key).filter(labels::contains)
        }
        val cooldowns = sharedPools.cooldownsFor(key)
        val polling = quotaSeams.pollingFor(ctx, quotaProbes, accountOrders, sharedPools)
        val seed = SharedPool(key, pool, primaryQuota, accountQuotas, emptyList(), polling, cooldowns)
        val stores = headStoresOf(ctx, seed)
        val pollers = polling.start(wired, stores, accountQuotas, providerAssembly, providerHolds)
        if (wired.auth is ClientAuthProvider) {
            primaryQuota.credentialListener = CredentialQuotaFiles(statePaths.quotaFile(key), log)
        }
        return stores to seed.copy(pollers = pollers)
    }

    /** gateway-no-heads: [ctx]'s provider assembled for the gateway, with no head to serve it: the provider and its
     *  credential, the books it accounts into (the same books a head of the same identity has), and the runtime a gateway
     *  turn runs through. [layer] is the config layer it reads (the provider's own overrides), and [registers] is whether the runtime takes the console's per-key registries, which a head of
     *  the same key already holds. */
    internal fun assembleRoute(ctx: ProviderBuild, layer: String, registers: Boolean): AssembledRoute {
        val key = ctx.key
        if (swept.add(key)) {
            keptFiles.atStart(
                key,
                codeMode = ctx.providerCfg.codeModeEnabled,
                trace = ctx.cfg.trace,
                traceWritten = null,
            )
        }
        val wired = providerAssembly.buildProvider(ctx)
        val shared = booksOf(ctx, wired)
        sharedPools.bindRoute(shared)
        val scope = RouteScope(layer, registers, forwardClientAuth = wired.auth is ClientAuthProvider)
        return AssembledRoute(wired, shared, serving.runtimeFor(ctx, wired, shared, scope))
    }

    // Common assembly shared by every provider: stores, the generic HeadServer, launch spec.
    internal fun assembleHead(ctx: ProviderBuild, controlPort: Int): ManagedHead {
        val key = ctx.key
        val cfg = ctx.cfg
        if (swept.add(key)) {
            keptFiles.atStart(
                key,
                codeMode = ctx.providerCfg.codeModeEnabled,
                trace = cfg.trace,
                traceWritten = ctx.head.overrides[Knob.TRACE.key],
            )
        }
        val wired = providerAssembly.buildProvider(ctx)
        val shared = booksOf(ctx, wired)
        sharedPools.bindRoute(shared)
        val stores = shared.stores
        val quotaPollers = shared.quota.pollers
        // Derived from the CREDENTIAL, never from the declared string. The bypass is safe only
        // because splice holds nothing for this head, so it reads the artifact that IS that fact:
        // `wired.auth`. ProviderAssembly rejects registered client auth on non-passthrough dialects
        // before this point; this structural check independently ensures no ClientAuthProvider means
        // no bypass. Caller auth rides upstream only on heads that do get it; every other head keeps
        // enforcing the management key.
        val forwardClientAuth = wired.auth is ClientAuthProvider
        val server = serving.observedHead(ctx, wired, stores, forwardClientAuth, shared.recordings)
        // DR-81: key presence is NOT baked into the spec — it is a per-launch read of the SAME
        // wired credential, so `splice key set`/unset changes the very next launch. Non-api-key
        // auth reads true: capture/advertiser stay disarmed, which is the safe side.
        val keyPresence = keyPresence(wired)
        return ManagedHead(
            head = server,
            auth = wired.auth,
            sources = sourcesFor(key, stores, quotaPollers, TurnPrice(ctx.catalog), perfSources.kept),
            usageWarning = UsageWarningSource {
                ctx.cfg.current().let { UsageWarning(warnPct = it.usageWarnPct, warnTokens5h = it.usageWarnTokens5h) }
            },
            authSurface = HeadAuthSurface(
                authKind = ctx.providerCfg.auth.kind,
                dialect = DialectWires.name(ctx.providerCfg.dialect),
                keyPresence = keyPresence,
                accountPool = accountPoolSource(key, wired, shared),
                accountAuth = accountPools.authSource(wired),
                plans = stores.telemetry.economics.plans,
            ),
            launchSpec = launchSpecFactory.launchSpecFor(
                ctx,
                controlPort,
                forwardClientAuth = forwardClientAuth,
            ),
            statusline = StatuslineContext(catalog = ctx.catalog, clientWindows = stores.clientWindows),
        )
    }

    private fun accountPoolSource(key: String, wired: Wired, shared: ProviderBooks): HeadAccountPoolSource? {
        val pool = shared.stores.accounts.pool
        val (policyKey, ownsPolicy) = sharedPools.claimPolicyOwner(pool, key)
        val limitResets = shared.quota.polling.limitResets(wired, log)
        return if (ownsPolicy) {
            accountPools.source(pool, policyKey, accountOrders, accountPins, limitResets)
        } else {
            accountPools.sharedSource(pool, policyKey, accountOrders, accountPins, limitResets)
        }
    }

    private fun sourcesFor(
        key: String,
        stores: HeadStores,
        quotaPollers: List<QuotaPoller>,
        price: TurnPrice,
        /** The same window the store trims by, so the hours it backfills reach as far as the ones
         *  it keeps. Left at its default, an install that keeps 90 days or forever rebuilt only 35. */
        kept: KeptHistory,
    ): HeadSources {
        val perfRows = perfSources.rowsFor(key)
        return HeadSources(
            usage = UsageStoreSource(stores.usageStore, stores.quota, quotaPollers),
            compact = CompactStatsSource(stores.telemetry.compactStats),
            logs = LogFileSource(statePaths.logsDir.resolve("daemon.log"), "[$key]"),
            perf = PerfStatsSource(stores.telemetry.perfStats),
            perfRows = perfRows,
            economics = EconomicsStoreSource(stores.telemetry.economics, perfRows, price, kept),
            trace = stores.trace?.recording,
        )
    }

    private fun keyPresence(wired: Wired): splice.launch.KeyPresenceProbe = splice.launch.KeyPresenceProbe {
        (wired.auth as? ApiKeyAuthProvider)?.hasKeyNow() != false
    }

    /** Who covers a request on this command's accounts: its credential says subscription or key, and its pool and quota
     *  say which accounts have a plan. The one answer the rollup, the rebuilt hours and the pages share. */
    private fun planLookup(ctx: ProviderBuild, pool: HeadAccountPoolSource?, quota: QuotaTracker): AccountPlanLookup {
        val auth = AuthKindRegistry.from(ctx.providerCfg.auth.kind)
        val kind = if (auth?.isOAuth == true || auth == AuthKind.Client) UsageBilling.SUBSCRIPTION else null
        return AccountPlanLookup(kind, pool, HeadQuotaPlan { quota.snapshot()?.plan })
    }

    private fun headStoresOf(ctx: ProviderBuild, shared: SharedPool): HeadStores = HeadStores(
        usageStore = UsageStore(statePaths.usageFile(ctx.key), statePaths.ratelimitFile(ctx.key)),
        telemetry = HeadTelemetryStores(
            compactStats = CompactStats(statePaths.compactStatsFile(ctx.key)),
            // V4-133's archive, wired: a generation the 64 MB rotate retires is kept for the
            // person's history window instead of discarded. The window is the SAME one the economics
            // store below trims against: one setting, two files (Settings > Your data, Oct 10, 2026).
            // The archive stays wired even for a window that keeps nothing, because a head that rolls
            // 64 MB during the day would otherwise discard the turns today's budget reads; the sweep
            // drops those generations at midnight instead.
            perfStats = PerfStats(
                statePaths.perfStatsFile(ctx.key),
                archiveDir = statePaths.perfArchiveDir,
                kept = perfSources.kept,
                // V4-244: each session's running total, fed by the rows this store appends and priced at
                // each row's own model's card, against the same catalog the economics store uses.
                totals = SessionTotals(statePaths.sessionTotalsFile(ctx.key), TurnPrice(ctx.catalog)),
            ),
            // V4-221: each turn priced at its own model's card, against the same catalog the budget uses.
            economics = EconomicsStore(
                statePaths.economicsFile(ctx.key),
                TurnPrice(ctx.catalog),
                kept = perfSources.kept,
                plans = planLookup(ctx, accountPools.source(shared.pool), shared.primaryQuota)
                    .let { lookup -> AccountOnPlan { lookup.covers(it) } },
            ),
        ),
        quota = shared.primaryQuota,
        accounts = HeadAccountStores(
            pool = shared.pool,
            quotas = shared.accountQuotas,
            fallbacks = SessionFallbacks { session -> sessionFallbacks.of(session) },
            upstreamCooldowns = shared.cooldowns,
        ),
        clientWindows = ClientWindows(store = statePaths.clientWindowsFile(ctx.key), log = log),
        trace = traceStores.forHead(ctx.key, ctx.cfg),
        providerHold = shared.cooldowns.store,
    )

    /** V4-216: the head's kept compaction answers. Not in [HeadStores]: only the head reads them, no
     *  control-plane adapter, so there is no second holder that must share the instance. */
    private fun recordings(ctx: ProviderBuild): FileCompactionRecordings =
        FileCompactionRecordings(
            statePaths.compactionRecordingsDir(ctx.key),
            log,
            keeping = KeepingRecordings { ctx.cfg.current().keepCompactionSummaries },
        )

    /** The tracker of a head that wired no account of its own: its readings and their history sit under the head. */
    private fun ownQuota(key: String): QuotaTracker = QuotaTracker(
        statePaths.quotaFile(key),
        extraFamily = CodexQuotaHeaderFamily(),
        history = QuotaHistory(statePaths.quotaFile(key), HISTORY_OWN_LOGIN),
    )

    /** The primary's snapshot stays where every install before 0.4.0 wrote it (per HEAD, under the
     *  state dir): an upgrade boots with its windows intact, and two heads of one kind never share a
     *  file. Labeled accounts persist next to their credential. Each reading also names the account its credential
     *  proves (Codex), and that account keeps its own copy beside the head's file (`codex-quota-<ref>.json`), so a
     *  sign-in moved to another account no longer overwrites the first one's windows with no name (Oct 15, 2026). */
    private fun accountQuotas(key: String, wired: Wired): MutableMap<String, QuotaTracker> =
        wired.accounts.associateTo(java.util.concurrent.ConcurrentHashMap()) { account ->
            val file =
                if (account.primary && account.nativePlace == null) statePaths.quotaFile(key) else account.quota.file
            val named = QuotaAccountFiles(statePaths.quotaFile(key), account.label, account.auth)
            // Every login of the head writes its changes to the head's one history file, under its own name.
            val history = QuotaHistory(statePaths.quotaFile(key), account.label)
            account.label to QuotaTracker(
                file,
                extraFamily = CodexQuotaHeaderFamily(),
                accounts = named,
                history = history,
            ).also {
                it.credentialListener = account.quota.read as? splice.head.usage.CredentialQuotaListener
            }
        }
}
