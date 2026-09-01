// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.kimiOauthAuth) @ ed5c868 — invariants unchanged:
// the device identity is built FIRST because its X-Msh-* headers ride the refresh call itself,
// then the auth provider that refreshes against them.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.KimiRefresh
import splice.app.TopologyLoader
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.HeadScopedLogs
import splice.core.util.LogSink
import splice.provider.kimi.KimiAuthProvider
import splice.provider.kimi.KimiDeviceIdentity
import splice.provider.kimi.KimiOAuthEndpoints
import java.nio.file.Paths

/** Kimi's device-flow OAuth construction: the device identity is built FIRST because its X-Msh-*
 *  headers ride the refresh call itself, then the auth provider that refreshes against them. */
internal class KimiOAuth(
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val kimiRefresh: KimiRefresh,
) {
    internal fun kimiOauthAuth(ctx: ProviderBuild): Pair<RefreshableAuthProvider, KimiDeviceIdentity> {
        val authPath = Paths.get(
            TopologyLoader.expandHome(ctx.providerCfg.auth.file ?: "~/.kimi/credentials/kimi-code.json"),
        )
        val identity = KimiDeviceIdentity(deviceIdPath = authPath.resolveSibling("device_id"))
        val identityHeaders = identity.headers()
        val tokenUrl = KimiOAuthEndpoints.tokenUrl(System::getenv)
        val auth = KimiAuthProvider(
            authPath = authPath,
            authCacheMs = ctx.cfg.authCacheMs,
            refreshCall = { rt -> kimiRefresh.refresh(tokenUrl, rt, identityHeaders) },
            prefetchScope = probeScope,
            // JW-03: [<headKey>] first, so [kimi-auth] refresh lines reach the head's tail
            log = HeadScopedLogs.headScopedLog(ctx.key, log),
        )
        return auth to identity
    }
}
