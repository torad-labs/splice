// NEW: native-place edits never touch a pool login that happens to have the same name.
package splice.app.auth.claude

import splice.accounts.signin.AccountMutation
import splice.client.ClaudeLoginResult
import splice.client.ClaudeLogins
import splice.core.util.Cancellables

/** File operations borrow the native owner's mutation claim. They do not own a scope or a second login lock. */
internal class ClaudeLoginEdits(private val reads: ClaudeLoginRead) {
    fun name(location: ClaudeLoginLocation, logins: ClaudeLogins): String? =
        reads.credentialKey(location)?.let(logins::labelForCredential)

    fun relabel(location: ClaudeLoginLocation, logins: ClaudeLogins, label: String): AccountMutation {
        val old = name(location, logins)
            ?: return AccountMutation.Refused("this login has no credential-bound saved name")
        return Cancellables.runCatchingCancellable {
            when (val result = logins.relabel(old, label)) {
                ClaudeLoginResult.Ok, is ClaudeLoginResult.Done -> AccountMutation.Ok
                is ClaudeLoginResult.Refused -> AccountMutation.Refused(result.reason)
            }
        }.getOrElse { AccountMutation.Refused("this saved name could not be changed (${it::class.simpleName})") }
    }
}
