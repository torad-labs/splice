// PORT-OF: splice/app/Daemon.kt (assembleHead) @ ed5c868 — invariants unchanged: the outer shell
// of head assembly — builds the three stores, calls the provider dispatch and the two factories
// below, computes apiKeyPresent and forwardClientAuth side by side (both are "what shape is this
// credential" reads of the SAME wired.auth), and assembles the ManagedHead record.
package splice.app.head

import splice.app.CompactStatsSource
import splice.app.LogFileSource
import splice.app.PerfStatsSource
import splice.app.UsageStoreSource
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.control.ManagedHead
import splice.core.auth.ClientAuthProvider
import splice.core.config.StatePaths
import splice.gateway.compact.CompactStats
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.provider.openai.ApiKeyAuthProvider

internal class ManagedHeadFactory(
    private val statePaths: StatePaths,
    private val providerAssembly: ProviderAssembly,
    private val headServerFactory: HeadServerFactory,
    private val launchSpecFactory: LaunchSpecFactory,
) {
    // Common assembly shared by every provider: stores, the generic HeadServer, launch spec.
    internal fun assembleHead(ctx: ProviderBuild, controlPort: Int): ManagedHead {
        val key = ctx.key
        val cfg = ctx.cfg
        val wired = providerAssembly.buildProvider(ctx)
        val stores = HeadStores(
            usageStore = UsageStore(statePaths.usageFile(key), statePaths.ratelimitFile(key)),
            compactStats = CompactStats(statePaths.compactStatsFile(key)),
            perfStats = PerfStats(statePaths.perfStatsFile(key)),
        )
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
            usage = UsageStoreSource(stores.usageStore),
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
            keyPresence = keyPresence,
        )
    }
}
