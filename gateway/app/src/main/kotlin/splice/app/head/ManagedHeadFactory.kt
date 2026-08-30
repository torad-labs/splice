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
        // Derived from the CREDENTIAL, never from the declared string. The bypass is only
        // safe because splice holds nothing for this head, so it reads the one artifact that
        // IS that fact: `wired.auth`. Deriving it from `auth.kind == "client"` instead made
        // the two halves independent — [ProviderAssembly.buildProvider] dispatches on
        // DIALECT first, so `kind = "client"` on openai-chat / openai-responses fell through
        // to ApiKeyAuthProvider and produced a head that opened the mgmt-key door while
        // still holding a real vendor key. Structurally impossible now: no
        // ClientAuthProvider, no bypass. The caller's own auth headers are what ride
        // upstream on the heads that do get it; every other head keeps enforcing the key.
        val forwardClientAuth = wired.auth is ClientAuthProvider
        val apiKeyPresent = (wired.auth as? ApiKeyAuthProvider)?.hasKeyNow() != false
        val server = headServerFactory.headServerFor(ctx, wired.provider, stores, forwardClientAuth)
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
                keyPresent = apiKeyPresent,
                forwardClientAuth = forwardClientAuth,
            ),
            perf = PerfStatsSource(stores.perfStats),
        )
    }
}
