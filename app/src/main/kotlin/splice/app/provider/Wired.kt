// PORT-OF: splice/app/Daemon.kt (Wired) @ ed5c868 — the provider keeps its legacy primary auth,
// while OAuth heads also carry every discovered credential into head-local account-pool assembly.
package splice.app.provider

import splice.core.auth.RefreshableAuthProvider
import splice.upstream.CredentialHeaders
import splice.upstream.Provider
import java.nio.file.Path

/** One discovered OAuth account, with secrets retained behind [auth]. */
internal data class WiredAccount(
    val label: String,
    val primary: Boolean,
    val auth: RefreshableAuthProvider,
    val quotaFile: Path,
    val credentialPresent: Boolean = true,
    val extraHeaders: CredentialHeaders? = null,
)

/** Chooses the legacy primary when readable, otherwise the first readable labeled credential. */
internal object WiredAccounts {
    fun providerAccount(accounts: List<WiredAccount>): WiredAccount {
        val readablePrimary = accounts.singleOrNull { it.primary && it.credentialPresent }
        return readablePrimary ?: accounts.firstOrNull(WiredAccount::credentialPresent)
            ?: accounts.single(WiredAccount::primary)
    }
}

/** Provider + default auth chosen by dispatch, plus OAuth accounts when this is a pooled head. */
internal data class Wired(
    val provider: Provider,
    val auth: RefreshableAuthProvider,
    val accounts: List<WiredAccount> = emptyList(),
) {
    init {
        val defaultAuth = accounts.takeIf { it.isNotEmpty() }
            ?.let(WiredAccounts::providerAccount)
            ?.auth
        require(defaultAuth == null || defaultAuth === auth) {
            "wired OAuth accounts must contain the provider's default auth"
        }
    }
}
