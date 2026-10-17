// NEW: native-place edits never touch a pool login that happens to have the same name.
package splice.app.auth.claude

import splice.accounts.signin.AccountMutation
import splice.client.ClaudeLoginResult
import splice.client.ClaudeLogins
import splice.client.HeadSessions
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Files
import java.util.UUID

/** File operations borrow the native owner's mutation claim. They do not own a scope or a second login lock. */
internal class ClaudeLoginEdits(private val reads: ClaudeLoginRead) {
    fun name(location: ClaudeLoginLocation, logins: ClaudeLogins): String? =
        reads.credentialKey(location)?.let(logins::labelForCredential)

    fun remove(location: ClaudeLoginLocation, logins: ClaudeLogins, sessions: HeadSessions): AccountMutation {
        if (sessions is HeadSessions.Unreadable) {
            return AccountMutation.Refused("this login's active sessions are unknown")
        }
        if (sessions is HeadSessions.Read && sessions.live.isNotEmpty()) {
            return AccountMutation.Refused(
                "this login is in use by active Claude sessions; stop them before removing it",
            )
        }
        return Cancellables.runCatchingCancellable {
            if (!Files.exists(location.credentials)) {
                return@runCatchingCancellable AccountMutation.Refused("no live login")
            }
            val label = name(location, logins)
            val backup = location.storeDir.resolve("removed").resolve("${UUID.randomUUID()}.credentials.json")
            SecureFile.writeAtomic0600(backup, Files.readString(location.credentials))
            Files.delete(location.credentials)
            if (label != null) logins.remove(label)
            AccountMutation.Ok
        }.getOrElse { AccountMutation.Refused("this native login could not be removed (${it::class.simpleName})") }
    }

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
