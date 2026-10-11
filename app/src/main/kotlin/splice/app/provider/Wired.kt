// PORT-OF: splice/app/Daemon.kt (Wired) @ ed5c868 — the provider keeps its legacy primary auth,
// while OAuth heads also carry every discovered credential into head-local account-pool assembly.
package splice.app.provider

import splice.accounts.claude.ClaudeLoginPlaceId
import splice.core.auth.ClientAuthProvider
import splice.core.auth.RefreshableAuthProvider
import splice.upstream.CredentialHeaders
import splice.upstream.Provider
import splice.upstream.credentials.AccountQuotaSource
import java.nio.file.Path

/** One discovered OAuth account, with secrets retained behind [auth]. */
internal data class WiredAccount(
    val label: String,
    val primary: Boolean,
    val auth: RefreshableAuthProvider,
    val quota: WiredAccountQuota,
    val credential: WiredAccountCredential = WiredAccountCredential(),
    val extraHeaders: CredentialHeaders? = null,
    /** A read-only native place, never a managed folder or the caller's forwarding placeholder. */
    val nativePlace: ClaudeLoginPlaceId? = null,
)

/** Where one account's quota is kept on disk, and how a live reading of it is taken. */
internal data class WiredAccountQuota(
    val file: Path,
    val read: AccountQuotaSource? = null,
)

/** Whether splice can load one account's credential, and when it cannot, why. */
internal data class WiredAccountCredential(
    val present: Boolean = true,
    /** V4-410: why splice will not load this account's credential (a symlinked file), in words. */
    val refusal: String? = null,
)

/** Chooses the legacy primary when readable, otherwise the first readable labeled credential. */
internal object WiredAccounts {
    fun providerAccount(accounts: List<WiredAccount>): WiredAccount {
        val readablePrimary = accounts.singleOrNull { it.primary && it.credential.present }
        return readablePrimary ?: accounts.firstOrNull { it.credential.present }
            ?: accounts.single(WiredAccount::primary)
    }
}

/** Reads an app-owned immutable account publication, without giving a value bundle a mutable handle. */
internal fun interface WiredAccountsRead {
    fun current(): List<WiredAccount>
}

/** Provider + default auth chosen by dispatch, plus OAuth accounts when this is a pooled head. */
internal data class Wired(
    val provider: Provider,
    val auth: RefreshableAuthProvider,
    val accounts: List<WiredAccount> = emptyList(),
    val membership: WiredAccountsRead? = null,
) {
    val liveAccounts: List<WiredAccount> get() = membership?.current() ?: accounts

    init {
        val defaultAuth = accounts.takeIf { it.isNotEmpty() }
            ?.let(WiredAccounts::providerAccount)
            ?.auth
        require(defaultAuth == null || auth is ClientAuthProvider || defaultAuth === auth) {
            "wired OAuth accounts must contain the provider's default auth"
        }
    }
}
