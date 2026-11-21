// PORT-OF: splice/app/Daemon.kt (assembleHead's UpstreamClient + Transport construction) @ ed5c868
// — invariants unchanged: the transport for one head. Split out because it is the only thing in
// this decomposition importing splice.upstream.transport.UpstreamClient.
package splice.app.head

import splice.app.provider.ProviderBuild
import splice.core.config.SpliceConfig
import splice.core.topology.ProviderCaFile
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.retry.ProviderHoldStore
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
import splice.upstream.transport.UpstreamTransport
import splice.upstream.transport.WriteBoundMs
import splice.upstream.transport.tls.TrustRoots
import java.nio.file.Path
import java.security.cert.X509Certificate

internal class UpstreamFactory {
    internal var performance: splice.app.observability.PerfTelemetry? = null
    internal fun upstreamFor(
        ctx: ProviderBuild,
        cfg: SpliceConfig,
        log: LogSink,
        providerHold: ProviderHoldStore?,
        configDir: Path,
    ): UpstreamClient = UpstreamClient(
        cfg.upstreamTimeoutMs,
        // CX-03: zstd request bodies — a TOML quirk, absent = plaintext. A quirk and
        // not a hardcoded provider check for two reasons: the operator opts in per
        // provider (proven only for ChatGPT, by codex-cli itself; xAI 400d on a
        // compressed body 2026-07-18), and the migration oracle's scratch topology
        // carries no quirk, so its 11 byte-exact fixtures replay plaintext — the
        // hardcoded check compressed the oracle's bodies and crashed its vendored
        // mock's JSON.parse, which was the source of every leaked harness daemon.
        zstdRequestBody = ctx.providerCfg.quirks.zstdRequestBody == true,
        // V4-272: a request the upstream stops taking is cut at firstByteTimeout, not the turn cap. custom-provider
        // (cp-tls): its certificate is trusted by Java's roots, the OS trust store, and this provider's own ca_file.
        client = UpstreamTransport(TrustRoots(log).manager(caFileRoots(ctx, configDir, log))).defaultClient(
            cfg.upstreamTimeoutMs,
            log,
            requestWriteTimeoutMs = cfg.firstByteTimeoutMs,
            requestWriteBound = WriteBoundMs { ctx.faultPlan.liveWatchdog().firstByteTimeout.inWholeMilliseconds },
        ).also { client ->
            splice.upstream.observability.UpstreamHttpRuntime.source(client)?.let { performance?.owners?.add(it) }
        },
        // Every wait is RetryEngine's (failure-handling slice 4): the retry curve and its knobs retired in 0.4.0.
        pacing = RetryPacing(),
        holdStore = providerHold,
    )

    /** The certificates [ctx]'s `ca_file` holds; none without one. Boot refused a bundle it could not read, so one
     *  that cannot be read now (removed since) is said in the log and the provider keeps the shared roots. */
    private fun caFileRoots(ctx: ProviderBuild, configDir: Path, log: LogSink): List<X509Certificate> {
        val raw = ctx.providerCfg.caFile ?: return emptyList()
        val read = Cancellables.runCatchingCancellable { ProviderCaFile(raw, configDir).certificates() }
        return read.getOrElse { failure ->
            log("[tls] providers.${ctx.head.provider}.ca_file could not be read: ${SafeFailureText.render(failure)}\n")
            emptyList()
        }
    }
}
