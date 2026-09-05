// PORT-OF: splice/app/Daemon.kt (assembleHead's HeadServer + HeadDeps construction) @ ed5c868 —
// invariants unchanged: upstream, inferenceToken, forwardClientAuth, the per-head InflightGate,
// ShadowClassifier and the shared RequestMaterializationGate. `val key = ctx.key` is kept so
// kt-head-scoped-config-must-be-keyed still covers this head-scoped function.
package splice.app.head

import splice.app.provider.ProviderBuild
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.util.LogSink
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.head.RequestMaterializationGate
import splice.spi.InflightGate
import splice.spi.Provider

internal class HeadServerFactory(
    private val config: ConfigService,
    private val mgmtKey: MgmtKey,
    private val log: LogSink,
) {
    private val upstreamFactory = UpstreamFactory()
    private val requestMaterializationGate = RequestMaterializationGate()

    internal fun headServerFor(
        ctx: ProviderBuild,
        provider: Provider,
        stores: HeadStores,
        forwardClientAuth: Boolean,
    ): HeadServer {
        val key = ctx.key
        val cfg = ctx.cfg
        return HeadServer(
            provider = provider,
            listenPort = ctx.head.port,
            deps = HeadDeps(
                upstream = upstreamFactory.upstreamFor(ctx, cfg, log),
                inferenceToken = mgmtKey.get(),
                forwardClientAuth = forwardClientAuth,
                // Re-read per head on EVERY admission (still hot-resizable): the ceiling belongs to
                // the upstream ACCOUNT, not the gateway. One shared value meant a workflow fan-out
                // admitted 100 concurrent streams into a single account, 429'd, and armed the shared
                // cooldown — measured 67% turn failure at inflight=100 vs 0.3% at <=14 (perf jsonl,
                // 2026-07-24). Per-head lets a slow upstream sit low while a fast one stays high.
                gate = InflightGate(
                    maxInflight = { config.getConfig(key).maxInflight },
                    maxQueued = { config.getConfig(key).maxQueued },
                ),
                shadow = ShadowClassifier(log = log),
                compactStats = stores.compactStats,
                mirrorReasoning = cfg.mirrorReasoning,
                usageStore = stores.usageStore,
                perfStats = stores.perfStats,
                quota = stores.quota,
                clientWindows = stores.clientWindows,
                log = log,
                requestMaterializationGate = requestMaterializationGate,
            ),
        )
    }
}
