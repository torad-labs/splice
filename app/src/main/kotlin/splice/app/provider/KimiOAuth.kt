// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.kimiOauthAuth) @ ed5c868 — each pooled
// credential keeps the Kimi identity used by both its refresh call and its upstream turns.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.auth.KimiRefresh
import splice.app.auth.OAuthAccountFiles
import splice.app.daemon.TopologyLoader
import splice.core.auth.RefreshableAuthProvider
import splice.core.topology.AuthKind
import splice.core.util.HeadScopedLogs
import splice.core.util.LogSink
import splice.provider.kimi.KimiAuthProvider
import splice.provider.kimi.KimiDeviceIdentity
import splice.provider.kimi.KimiOAuthEndpoints
import java.nio.file.Path
import java.nio.file.Paths

/** One Kimi OAuth credential and the device identity that must travel with it. */
internal data class KimiOAuthAccount(
    val label: String,
    val primary: Boolean,
    val auth: RefreshableAuthProvider,
    val quotaFile: Path,
    val credentialPresent: Boolean,
    val identity: KimiDeviceIdentity,
)

/** Kimi's device-flow OAuth construction, repeated once per discovered account file. */
internal class KimiOAuth(
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val kimiRefresh: KimiRefresh,
) {
    private val accountFiles = OAuthAccountFiles()

    internal fun kimiOauthAccounts(ctx: ProviderBuild): List<KimiOAuthAccount> {
        val primaryPath = Paths.get(
            TopologyLoader.expandHome(ctx.providerCfg.auth.file ?: AuthKind.KimiOAuth.authFile),
        )
        val tokenUrl = KimiOAuthEndpoints.tokenUrl(System::getenv)
        return accountFiles.discover(AuthKind.KimiOAuth, primaryPath).map { file ->
            val identity = KimiDeviceIdentity(deviceIdPath(file.credentialFile, file.primary, file.label))
            KimiOAuthAccount(
                label = file.label,
                primary = file.primary,
                auth = kimiAuth(ctx, file.credentialFile, identity, tokenUrl),
                quotaFile = file.quotaFile,
                credentialPresent = file.credentialPresent,
                identity = identity,
            )
        }
    }

    internal fun providerAccount(accounts: List<KimiOAuthAccount>): KimiOAuthAccount {
        val readablePrimary = accounts.singleOrNull { it.primary && it.credentialPresent }
        return readablePrimary ?: accounts.firstOrNull(KimiOAuthAccount::credentialPresent)
            ?: accounts.single(KimiOAuthAccount::primary)
    }

    private fun kimiAuth(
        ctx: ProviderBuild,
        authPath: Path,
        identity: KimiDeviceIdentity,
        tokenUrl: String,
    ): RefreshableAuthProvider {
        val identityHeaders = identity.headers()
        return KimiAuthProvider(
            authPath = authPath,
            authCacheMs = ctx.cfg.authCacheMs,
            refreshCall = { refreshToken -> kimiRefresh.refresh(tokenUrl, refreshToken, identityHeaders) },
            prefetchScope = probeScope,
            // JW-03: [<headKey>] first, so [kimi-auth] refresh lines reach the head's tail.
            log = HeadScopedLogs.headScopedLog(ctx.key, log),
        )
    }

    private fun deviceIdPath(authPath: Path, primary: Boolean, label: String): Path =
        authPath.resolveSibling(if (primary) "device_id" else "$label-device_id")
}
