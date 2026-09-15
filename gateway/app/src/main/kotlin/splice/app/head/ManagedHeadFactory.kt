// PORT-OF: splice/app/Daemon.kt (assembleHead) @ ed5c868 — invariants unchanged: the outer shell
// of head assembly — builds the three stores, calls the provider dispatch and the two factories
// below, computes apiKeyPresent and forwardClientAuth side by side (both are "what shape is this
// credential" reads of the SAME wired.auth), and assembles the ManagedHead record.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import splice.app.AuthHttpClientFactory
import splice.app.CompactStatsSource
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
import splice.core.config.StatePaths
import splice.core.model.ClientWindows
import splice.core.util.LogSink
import splice.gateway.compact.CompactStats
import splice.gateway.perf.PerfStats
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexQuotaHeaderFamily
import splice.provider.muse.MuseAuthProvider
import splice.provider.openai.ApiKeyAuthProvider

internal fun interface StartQuotaPoller {
    operator fun invoke(head: String, probe: QuotaProbe, tracker: QuotaTracker)
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
    private val startQuotaPoller: StartQuotaPoller = StartQuotaPoller { head, probe, tracker ->
        QuotaPoller(probeScope, head, probe, tracker, log).start()
    },
) {
    private val quotaProbes by lazy { QuotaProbes(AuthHttpClientFactory().create()) }
    private val accountPools = HeadAccountPools()

    // Common assembly shared by every provider: stores, the generic HeadServer, launch spec.
    internal fun assembleHead(ctx: ProviderBuild, controlPort: Int): ManagedHead {
        val key = ctx.key
        val cfg = ctx.cfg
        val wired = providerAssembly.buildProvider(ctx)
        val accountQuotas = accountQuotas(key, wired)
        val primaryQuota = wired.accounts.singleOrNull { it.primary }
            ?.let { accountQuotas.getValue(it.label) }
            ?: QuotaTracker(statePaths.quotaFile(key), extraFamily = CodexQuotaHeaderFamily())
        val stores = HeadStores(
            usageStore = UsageStore(statePaths.usageFile(key), statePaths.ratelimitFile(key)),
            compactStats = CompactStats(statePaths.compactStatsFile(key)),
            perfStats = PerfStats(statePaths.perfStatsFile(key)),
            quota = primaryQuota,
            accountPool = accountPools.build(wired, accountQuotas),
            accountQuotas = accountQuotas,
            clientWindows = ClientWindows(store = statePaths.clientWindowsFile(key), log = log),
        )
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
            keyPresence = keyPresence,
            catalog = ctx.catalog,
            clientWindows = stores.clientWindows,
            accountPool = accountPools.source(stores.accountPool),
            accountAuth = accountPools.authSource(wired),
        )
    }

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
        // Subscription heads have a usage endpoint. Every OAuth account gets its own persisted
        // snapshot and poller; non-pooled heads retain the legacy single tracker path.
        if (wired.accounts.isEmpty()) {
            quotaProbes.forHead(ctx, wired.auth, usageFields(wired.auth))?.let { probe ->
                startQuotaPoller(ctx.key, probe, stores.quota)
            }
            return
        }
        wired.accounts.forEach { account ->
            quotaProbes.forHead(ctx, account.auth, usageFields(account.auth))?.let { probe ->
                startQuotaPoller(ctx.key, probe, stores.accountQuotas.getValue(account.label))
            }
        }
    }

    private fun usageFields(auth: AuthProvider): UsageFields? =
        (auth as? MuseAuthProvider)?.let(::MuseAuthUsageFields)
}
