// NEW: the Claude half of failover within one provider (operator ruling, Oct 3, 11:44 PM CT: "each provider gets a
// head, each head can have multiple subscriptions"; Oct 4, 12:00 AM CT: heads are templates, so this serves ANY head
// built from the Claude template, under any key, plain `claude` included).
//
// Turns one command's accounts into the pool entries the head is assembled from. Entry one is the CALLER's own Claude
// Code sign-in: it forwards the caller's credential (ClientAuthProvider), so it is the head's own default auth and
// the pool's primary, and the pre-pool path is what a command with no added account still gets. Each added account
// follows, oldest first, with the credential of its own folder.
//
// A folder splice cannot read is NOT silently dropped: it rides on the last account's row as a refusal, because
// Accounts shows a refusal in words and the alternative is a login that quietly vanished.
package splice.app.provider

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
) {
    /** [head]'s pool entries, or none when nobody has added an account to it. [caller] is the head's own forwarding
     *  credential, which stays the primary: a pool whose primary were an added account would send someone else's
     *  login for the person at the keyboard. */
    fun accounts(head: String, caller: RefreshableAuthProvider): List<WiredAccount> {
        val added = folders.accounts(head)
        if (added.isEmpty()) return emptyList()
        val unreadable = folders.unreadable(head)
        val entries = listOf(
            WiredAccount(
                label = OWN_SIGN_IN_LABEL,
                primary = true,
                auth = caller,
                quotaFile = statePaths.quotaFile(head),
            ),
        ) + added.map { account ->
            WiredAccount(
                label = account.label,
                primary = false,
                auth = ClaudeFolderAuth(account.directory, refresh = refresh),
                quotaFile = account.directory.resolve(QUOTA_FILE),
            )
        }
        return refusal(unreadable)?.let { why -> entries.dropLast(1) + entries.last().copy(refusal = why) } ?: entries
    }

    /** What Accounts says about the folders this command holds that are not readable logins. */
    private fun refusal(unreadable: List<String>): String? = unreadable.takeIf { it.isNotEmpty() }?.let { labels ->
        "the sign-in in ${labels.joinToString(", ") { "'$it'" }} is unreadable; sign in again"
    }
}

// why: each added account's quota snapshot lives beside its own credential, so no two logins share a window and
// removing an account takes its usage with it. The primary's stays in the per-head file every install already has.
private const val QUOTA_FILE = "quota.json"
