// PORT-OF: splice/app/Daemon.kt (assembleHead's UpstreamClient + Transport construction) @ ed5c868
// — invariants unchanged: the transport for one head. Split out because it is the only thing in
// this decomposition importing splice.spi.UpstreamClient.
package splice.app.head

import splice.app.provider.ProviderBuild
import splice.core.config.SpliceConfig
import splice.core.util.LogSink
import splice.spi.UpstreamClient
import splice.spi.UpstreamClient.Transport

internal class UpstreamFactory {
    internal fun upstreamFor(ctx: ProviderBuild, cfg: SpliceConfig, log: LogSink): UpstreamClient = UpstreamClient(
        cfg.firstByteTimeoutMs,
        cfg.upstreamTimeoutMs,
        cfg.upstreamRetries,
        // CX-03: zstd request bodies — a TOML quirk, absent = plaintext. A quirk and
        // not a hardcoded provider check for two reasons: the operator opts in per
        // provider (proven only for ChatGPT, by codex-cli itself; xAI 400d on a
        // compressed body 2026-07-18), and the migration oracle's scratch topology
        // carries no quirk, so its 11 byte-exact fixtures replay plaintext — the
        // hardcoded check compressed the oracle's bodies and crashed its vendored
        // mock's JSON.parse, which was the source of every leaked harness daemon.
        zstdRequestBody = ctx.providerCfg.quirks.zstdRequestBody == true,
        client = Transport().defaultClient(cfg.firstByteTimeoutMs, cfg.upstreamTimeoutMs, log),
    )
}
