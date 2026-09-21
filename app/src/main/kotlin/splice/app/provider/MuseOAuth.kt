// NEW: Muse's splice-owned account pool uses persisted inference keys and a one-shot mint seam.
// No Moonshot device identity or expiry prefetch: the account token is used only for key exchange.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.auth.OAuthAccountFiles
import splice.app.daemon.TopologyLoader
import splice.core.auth.RefreshableAuthProvider
import splice.core.topology.AuthKind
import splice.core.util.HeadScopedLogs
import splice.core.util.LogSink
import splice.provider.muse.MuseAuthProvider
import splice.provider.muse.MuseKeyMintCall
import java.nio.file.Path
import java.nio.file.Paths

/** One independently refreshable Muse account, with its own persisted quota state. */
internal data class MuseOAuthAccount(
    val label: String,
    val primary: Boolean,
    val auth: RefreshableAuthProvider,
    val quotaFile: Path,
    val credentialPresent: Boolean,
)

/** Discovery follows the same primary/backup policy as the other splice-owned OAuth pools. */
internal class MuseOAuth(
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val mintCall: MuseKeyMintCall,
) {
    private val accountFiles = OAuthAccountFiles()

    internal fun museOauthAccounts(ctx: ProviderBuild): List<MuseOAuthAccount> {
        val primaryPath = Paths.get(
            TopologyLoader.expandHome(ctx.providerCfg.auth.file ?: AuthKind.MuseOAuth.authFile),
        )
        return accountFiles.discover(AuthKind.MuseOAuth, primaryPath).map { file ->
            MuseOAuthAccount(
                label = file.label,
                primary = file.primary,
                auth = MuseAuthProvider(
                    authPath = file.credentialFile,
                    log = HeadScopedLogs.headScopedLog(ctx.key, log),
                    mintCall = mintCall,
                    authCacheMs = ctx.cfg.authCacheMs,
                    prefetchScope = probeScope,
                ),
                quotaFile = file.quotaFile,
                credentialPresent = file.credentialPresent,
            )
        }
    }

    internal fun providerAccount(accounts: List<MuseOAuthAccount>): MuseOAuthAccount {
        val readablePrimary = accounts.singleOrNull { it.primary && it.credentialPresent }
        return readablePrimary ?: accounts.firstOrNull(MuseOAuthAccount::credentialPresent)
            ?: accounts.single(MuseOAuthAccount::primary)
    }
}
