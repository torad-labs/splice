// PORT-OF: splice/app/Daemon.kt (assembleHead) @ ed5c868 — invariants unchanged: the outer shell
// of head assembly — builds the three stores, calls the provider dispatch and the two factories
// below, computes apiKeyPresent and forwardClientAuth side by side (both are "what shape is this
// credential" reads of the SAME wired.auth), and assembles the ManagedHead record.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import splice.app.AuthHttpClientFactory
import splice.app.CompactStatsSource
import splice.app.EconomicsStoreSource
import splice.app.LogFileSource
import splice.app.PerfRowsFileSource
import splice.app.PerfStatsSource
import splice.app.UsageStoreSource
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.Wired
import splice.app.quota.MuseAuthUsageFields
import splice.app.quota.QuotaPoller
import splice.app.quota.QuotaProbe
import splice.app.quota.QuotaProbes
import splice.app.quota.UsageFields
import splice.control.ManagedHead
import splice.core.auth.AuthProvider
import splice.core.auth.ClientAuthProvider
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.model.ClientWindows
import splice.core.util.LogSink
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.usage.EconomicsStore
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.provider.codex.CodexQuotaHeaderFamily
import splice.provider.muse.MuseAuthProvider
import splice.provider.openai.ApiKeyAuthProvider

internal fun interface StartQuotaPoller {
    operator fun invoke(head: String, probe: QuotaProbe, tracker: QuotaTracker, intervalMs: Long)
}

/** Observes the primary quota tracker at assembly so a test can see which tracker was wired.
 *  The production body is a no-op and nothing in production consumes the callback — unlike
 *  [StartQuotaPoller], whose default starts a real [QuotaPoller]. */
internal fun interface OnPrimaryQuota {
    operator fun invoke(tracker: QuotaTracker)
}

internal class ManagedHeadFactory(
    private val statePaths: StatePaths,
    private val providerAssembly: ProviderAssembly,
    private val headServerFactory: HeadServerFactory,
    private val launchSpecFactory: LaunchSpecFactory,
    /** The daemon's OWN scope (the same instance ProviderAssembly prefetches on): the quota pollers
     *  end with Daemon.stop() like every other background probe. */
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val startQuotaPoller: StartQuotaPoller = StartQuotaPoller { head, probe, tracker, intervalMs ->
        QuotaPoller(probeScope, head, probe, tracker, log, intervalMs = intervalMs).start()
    },
    private val onPrimaryQuota: OnPrimaryQuota = OnPrimaryQuota { _ -> },
) {
    private val quotaProbes by lazy { QuotaProbes(AuthHttpClientFactory().create()) }
    private val accountPools = HeadAccountPools()
    private val traceStores = HeadTraceStores(statePaths)

    // Common assembly shared by every provider: stores, the generic HeadServer, launch spec.
    internal fun assembleHead(ctx: ProviderBuild, controlPort: Int): ManagedHead {
        val key = ctx.key
        val cfg = ctx.cfg
        val wired = providerAssembly.buildProvider(ctx)
        val accountQuotas = accountQuotas(key, wired)
        val primaryQuota = wired.accounts.singleOrNull { it.primary }
            ?.let { accountQuotas.getValue(it.label) }
            ?: QuotaTracker(statePaths.quotaFile(key), extraFamily = CodexQuotaHeaderFamily())
        onPrimaryQuota(primaryQuota)
        val stores = headStores(ctx, wired, primaryQuota, accountQuotas)
        startQuotaPollers(ctx, wired, stores, cfg.quotaPollOff)
        val logFile = statePaths.logsDir.resolve("daemon.log")
        // Derived from the CREDENTIAL, never from the declared string. The bypass is safe only
        // because splice holds nothing for this head, so it reads the artifact that IS that fact:
        // `wired.auth`. ProviderAssembly rejects registered client auth on non-passthrough dialects
        // before this point; this structural check independently ensures no ClientAuthProvider means
        // no bypass. Caller auth rides upstream only on heads that do get it; every other head keeps
        // enforcing the management key.
        val forwardClientAuth = wired.auth is ClientAuthProvider
        val server = headServerFactory.headServerFor(ctx, wired.provider, stores, forwardClientAuth)
        // DR-81: key presence is NOT baked into the spec — it is a per-launch read of the SAME
        // wired credential, so `splice key set`/unset changes the very next launch. Non-api-key
        // auth reads true: capture/advertiser stay disarmed, which is the safe side.
        val keyPresence = splice.control.KeyPresenceProbe {
            (wired.auth as? ApiKeyAuthProvider)?.hasKeyNow() != false
        }
        return ManagedHead(
            head = server,
            auth = wired.auth,
            usage = UsageStoreSource(stores.usageStore, stores.quota),
            compact = CompactStatsSource(stores.compactStats),
            logs = LogFileSource(logFile, "[$key]"),
            warnPct = cfg.usageWarnPct,
            warnTokens5h = cfg.usageWarnTokens5h,
            authKind = ctx.providerCfg.auth.kind,
            launchSpec = launchSpecFactory.launchSpecFor(
                ctx,
                controlPort,
                forwardClientAuth = forwardClientAuth,
            ),
            perf = PerfStatsSource(stores.perfStats),
            perfRows = PerfRowsFileSource(statePaths.perfStatsFile(key)),
            economics = EconomicsStoreSource(stores.economics),
            keyPresence = keyPresence,
            catalog = ctx.catalog,
            clientWindows = stores.clientWindows,
            accountPool = accountPools.source(stores.accountPool),
            accountAuth = accountPools.authSource(wired),
        )
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
        compactStats = CompactStats(statePaths.compactStatsFile(ctx.key)),
        perfStats = PerfStats(statePaths.perfStatsFile(ctx.key)),
        economics = EconomicsStore(statePaths.economicsFile(ctx.key)),
        quota = primaryQuota,
        accountPool = accountPools.build(wired, accountQuotas),
        accountQuotas = accountQuotas,
        clientWindows = ClientWindows(store = statePaths.clientWindowsFile(ctx.key), log = log),
        trace = traceStores.forHead(ctx.key, ctx.cfg),
    )

    /** The primary's snapshot stays where every install before 0.4.0 wrote it (per HEAD, under the
     *  state dir): an upgrade boots with its windows intact, and two heads of one kind never share a
     *  file. Labeled accounts persist next to their credential. */
    private fun accountQuotas(key: String, wired: Wired): Map<String, QuotaTracker> =
        wired.accounts.associate { account ->
            val file = if (account.primary) statePaths.quotaFile(key) else account.quotaFile
            account.label to QuotaTracker(file, extraFamily = CodexQuotaHeaderFamily())
        }

    private fun startQuotaPollers(
        ctx: ProviderBuild,
        wired: Wired,
        stores: HeadStores,
        off: Boolean,
    ) {
        if (off) return
        // V4-110: the poll cadence is the quotaPollIntervalMs knob (floored in ConfigCoercion),
        // read per head from the merged+normalized map — always seeded, so `as Long` is safe.
        val intervalMs = ctx.cfg.asMap()[Knob.QUOTA_POLL_INTERVAL_MS.key] as Long
        // Subscription heads have a usage endpoint. Every OAuth account gets its own persisted
        // snapshot and poller; non-pooled heads retain the legacy single tracker path.
        if (wired.accounts.isEmpty()) {
            quotaProbes.forHead(ctx, wired.auth, usageFields(wired.auth))?.let { probe ->
                startQuotaPoller(ctx.key, probe, stores.quota, intervalMs)
            }
            return
        }
        wired.accounts.forEach { account ->
            quotaProbes.forHead(ctx, account.auth, usageFields(account.auth))?.let { probe ->
                startQuotaPoller(ctx.key, probe, stores.accountQuotas.getValue(account.label), intervalMs)
            }
        }
    }

    private fun usageFields(auth: AuthProvider): UsageFields? =
        (auth as? MuseAuthProvider)?.let(::MuseAuthUsageFields)
}
