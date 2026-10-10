// PORT-OF: splice/app/Daemon.kt (assembleHead) @ ed5c868 — invariants unchanged: the outer shell
// of head assembly — builds the three stores, calls the provider dispatch and the two factories
// below, computes apiKeyPresent and forwardClientAuth side by side (both are "what shape is this
// credential" reads of the SAME wired.auth), and assembles the ManagedHead record.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import splice.accounts.order.ACCOUNT_ORDER_FILE
import splice.accounts.order.AccountOrderStore
import splice.app.control.HeadAuthSurface
import splice.app.control.HeadSources
import splice.app.control.ManagedHead
import splice.app.control.StatuslineContext
import splice.app.control.UsageWarning
import splice.app.control.UsageWarningSource
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
import splice.core.util.LogSink
import splice.diagnostics.logs.LogFileSource
import splice.head.compact.CompactStats
import splice.head.compaction.FileCompactionRecordings
import splice.head.perf.PerfStats
import splice.head.perf.SessionTotals
import splice.head.usage.CredentialQuotaFiles
import splice.head.usage.EconomicsStore
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.oauth.AuthHttpClientFactory
import splice.provider.codex.CodexQuotaHeaderFamily
import splice.provider.openai.ApiKeyAuthProvider
import splice.usage.quota.ClientUserAgent
import splice.usage.quota.QuotaCadence
import splice.usage.quota.QuotaPoller
import splice.usage.quota.QuotaProbe
import splice.usage.quota.QuotaProbes
import splice.usage.quota.QuotaSnapshotSink

internal fun interface StartQuotaPoller {
    operator fun invoke(head: String, probe: QuotaProbe, tracker: QuotaTracker, intervalMs: Long): QuotaPoller?
}

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
    probeScope: CoroutineScope,
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
) {
    fun primaryWired(tracker: QuotaTracker) {
        onPrimaryQuota(tracker)
    }

    fun pollingFor(
        ctx: ProviderBuild,
        probes: QuotaProbes,
        orders: AccountOrderStore,
    ): HeadQuotaPolling = HeadQuotaPolling(ctx, probes, startQuotaPoller, clientUserAgent, orders)
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
        .also { playgroundProviders.bind(ctx.key, wired, stores.accounts.pool, it.providerReplies) }
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
    private val accountPools = HeadAccountPools()
    private val accountOrders = AccountOrderStore(statePaths.stateDir.resolve(ACCOUNT_ORDER_FILE))
    private val providerHolds = ProviderHoldFiles(statePaths, log)
    private val traceStores = HeadTraceStores(statePaths)
    private val keptFiles = HeadKeptFiles(statePaths, log)

    // Common assembly shared by every provider: stores, the generic HeadServer, launch spec.
    internal fun assembleHead(ctx: ProviderBuild, controlPort: Int): ManagedHead {
        val key = ctx.key
        val cfg = ctx.cfg
        keptFiles.atStart(
            key,
            codeMode = ctx.providerCfg.codeModeEnabled,
            trace = cfg.trace,
            traceWritten = ctx.head.overrides[Knob.TRACE.key],
        )
        val wired = providerAssembly.buildProvider(ctx)
        val accountQuotas = accountQuotas(key, wired)
        val primaryQuota = wired.accounts.singleOrNull { it.primary && it.nativePlace == null }
            ?.let { accountQuotas.getValue(it.label) }
            ?: QuotaTracker(statePaths.quotaFile(key), extraFamily = CodexQuotaHeaderFamily())
        quotaSeams.primaryWired(primaryQuota)
        val stores = headStores(ctx, wired, primaryQuota, accountQuotas)
        val quotaPollers = quotaSeams.pollingFor(ctx, quotaProbes, accountOrders)
            .start(wired, stores, accountQuotas, providerAssembly, providerHolds)
        // Derived from the CREDENTIAL, never from the declared string. The bypass is safe only
        // because splice holds nothing for this head, so it reads the artifact that IS that fact:
        // `wired.auth`. ProviderAssembly rejects registered client auth on non-passthrough dialects
        // before this point; this structural check independently ensures no ClientAuthProvider means
        // no bypass. Caller auth rides upstream only on heads that do get it; every other head keeps
        // enforcing the management key.
        val forwardClientAuth = wired.auth is ClientAuthProvider
        if (forwardClientAuth) primaryQuota.credentialListener = CredentialQuotaFiles(statePaths.quotaFile(key), log)
        val server = serving.observedHead(ctx, wired, stores, forwardClientAuth, recordings(key))
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
                keyPresence = keyPresence,
                accountPool = accountPools.source(stores.accounts.pool, key, accountOrders),
                accountAuth = accountPools.authSource(wired),
            ),
            launchSpec = launchSpecFactory.launchSpecFor(
                ctx,
                controlPort,
                forwardClientAuth = forwardClientAuth,
            ),
            statusline = StatuslineContext(catalog = ctx.catalog, clientWindows = stores.clientWindows),
        )
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
        )
    }

    private fun keyPresence(wired: Wired): splice.launch.KeyPresenceProbe = splice.launch.KeyPresenceProbe {
        (wired.auth as? ApiKeyAuthProvider)?.hasKeyNow() != false
    }

    /** Every file-backed store one head owns, built from its state paths. Its own method because
     *  the head WRITES these and the control-plane adapters READ them, and both must hold the SAME
     *  instances — a second store over the same path would serve the dashboard its own stale
     *  in-memory copy. (The economics store pushed assembleHead past detekt's LongMethod ceiling,
     *  which is the same pressure that made this a separate declaration in the original commit.) */
    private fun headStores(
        ctx: ProviderBuild,
        wired: Wired,
        primaryQuota: QuotaTracker,
        accountQuotas: Map<String, QuotaTracker>,
    ): HeadStores = HeadStores(
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
            ),
        ),
        quota = primaryQuota,
        accounts = HeadAccountStores(
            pool = accountPools.build(wired, accountQuotas, providerHolds.forAccounts(ctx.key, wired), primaryQuota),
            quotas = accountQuotas,
        ),
        clientWindows = ClientWindows(store = statePaths.clientWindowsFile(ctx.key), log = log),
        trace = traceStores.forHead(ctx.key, ctx.cfg),
        providerHold = providerHolds.forHead(ctx.key),
    )

    /** V4-216: the head's kept compaction answers. Not in [HeadStores]: only the head reads them, no
     *  control-plane adapter, so there is no second holder that must share the instance. */
    private fun recordings(key: String): FileCompactionRecordings =
        FileCompactionRecordings(statePaths.compactionRecordingsDir(key), log)

    /** The primary's snapshot stays where every install before 0.4.0 wrote it (per HEAD, under the
     *  state dir): an upgrade boots with its windows intact, and two heads of one kind never share a
     *  file. Labeled accounts persist next to their credential. */
    private fun accountQuotas(key: String, wired: Wired): MutableMap<String, QuotaTracker> =
        wired.accounts.associateTo(java.util.concurrent.ConcurrentHashMap()) { account ->
            val file =
                if (account.primary && account.nativePlace == null) statePaths.quotaFile(key) else account.quota.file
            account.label to QuotaTracker(file, extraFamily = CodexQuotaHeaderFamily()).also {
                it.credentialListener = account.quota.read as? splice.head.usage.CredentialQuotaListener
            }
        }
}
