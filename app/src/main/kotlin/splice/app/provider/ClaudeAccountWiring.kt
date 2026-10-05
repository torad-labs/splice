// NEW: the Claude half of failover within one provider (operator ruling, Oct 3, 11:44 PM CT: "each provider gets a
// head, each head can have multiple subscriptions"; Oct 4, 12:00 AM CT: heads are templates, so this serves ANY head
// built from the Claude template, under any key, plain `claude` included).
//
// Turns one command's accounts into the pool entries the head is assembled from. Entry one is the CALLER's own Claude
// Code sign-in: it forwards the caller's credential (ClientAuthProvider), so it is the head's own default auth and
// the pool's primary, and the pre-pool path is what a command with no added account still gets. Each added account
// follows, oldest first, with the credential of its own folder.
//
// A folder splice cannot read keeps its OWN row, with no credential and its own refusal in words (V4-410): the
// label is on the pool because somebody added it, and neither dropping it nor hanging its sentence on a working
// account's row tells the truth about which login needs signing in again.
package splice.app.provider

import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.app.auth.claude.ClaudeAccountFolders
import splice.app.auth.claude.ClaudeFolderAuth
import splice.app.auth.claude.ClaudeOAuthRefresh
import splice.app.auth.claude.ClaudeTokenRefresh
import splice.app.auth.claude.OWN_SIGN_IN_LABEL
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.StatePaths
import splice.core.util.LogSink

internal class ClaudeAccountWiring(
    private val statePaths: StatePaths,
    private val log: LogSink,
    private val folders: ClaudeAccountFolders = ClaudeAccountFolders(statePaths.stateDir),
    private val refresh: ClaudeTokenRefresh = ClaudeTokenRefresh(ClaudeOAuthRefresh(log)::rotate),
    private val identities: splice.app.auth.claude.ClaudeIdentityRefresh? = null,
    nativePlaces: ClaudeLoginPlacesSource = ClaudeLoginPlacesSource { null },
) {
    private val native = ClaudeNativeAccountWiring(statePaths, nativePlaces, log)

    /** Native places provide read-only selectable credentials. Without one, [caller] remains the forwarded primary;
     *  managed folders are always separate members, never a replacement for the native login owner. */
    fun accounts(head: String, caller: RefreshableAuthProvider): List<WiredAccount> {
        val added = folders.accounts(head)
        val places = native.accounts(head)
        if (added.isEmpty() && places.isEmpty()) return emptyList()
        val primary = places.ifEmpty {
            listOf(
                WiredAccount(
                    label = OWN_SIGN_IN_LABEL,
                    primary = true,
                    auth = caller,
                    quotaFile = statePaths.quotaFile(head),
                ),
            )
        }
        return primary + added.map { account ->
            WiredAccount(
                label = account.label,
                primary = false,
                auth = ClaudeFolderAuth(
                    account.directory,
                    refresh = refresh,
                    profiles = splice.app.auth.claude.ClaudeCredentialProfiles(statePaths.stateDir, log),
                    identities = identities,
                ),
                quotaFile = account.directory.resolve(QUOTA_FILE),
                credentialPresent = account.refusal == null,
                refusal = account.refusal,
            )
        }
    }
}

// why: each added account's quota snapshot lives beside its own credential, so no two logins share a window and
// removing an account takes its usage with it. The primary's stays in the per-head file every install already has.
private const val QUOTA_FILE = "quota.json"
