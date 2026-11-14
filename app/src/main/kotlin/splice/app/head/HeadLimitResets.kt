// NEW: Nov 13, 2026: a head's Claude logins spend a limit reset on a person's word, and the head picks the login up at
// once. A turn held on a plan limit goes on when a reading of the login shows the limit reset (PlanHold.weigh), and
// while a head holds a turn its logins are read each time the poller's floor allows, about a minute (HeldLoginReads).
// After a spend the login is read again past that floor, so the turns waiting for the limits it cleared go on at once.
package splice.app.head

import splice.accounts.pool.HeadAccountResetSource
import splice.accounts.pool.LoginResetSpend
import splice.accounts.pool.LoginResets
import splice.accounts.pool.RESET_SPENT
import splice.accounts.pool.RESET_UNKNOWN_KIND
import splice.app.auth.claude.ClaudeLimitResets
import splice.app.auth.claude.ResetKinds
import splice.app.provider.ClaudeLoginHeaders
import splice.app.provider.WiredAccount
import splice.app.provider.WiredAccountsRead
import splice.core.auth.Credentials
import splice.core.util.LogSafe
import splice.core.util.LogSink

// why: what a login whose token splice cannot send says, a read or a spend alike.
private const val NO_TOKEN = "splice holds no token it can send for this login; sign it in again."

// why: what a spend asked for a kind no Claude login has says.
private const val NO_SUCH_KIND = "There is no such reset; ask for fable, weekly, full or 5-hour."

/** A reading of one login's usage taken now, past the wait the poller keeps between reads. */
internal interface LoginReadAgain {
    /** True when the login's provider gave a new reading; false when it gave none or the head reads no such login. */
    suspend fun readAgain(label: String): Boolean
}

/** The limit resets of a head's Claude logins: listed and spent by label, never by splice itself. */
internal class HeadLimitResets(
    private val head: String,
    private val logins: WiredAccountsRead,
    private val reads: LoginReadAgain,
    private val client: ClaudeLimitResets,
    private val log: LogSink,
) : HeadAccountResetSource {
    override fun resetLabels(): List<String> = claude().map(WiredAccount::label)

    override suspend fun resets(label: String): LoginResets? {
        val login = login(label) ?: return null
        return token(login)?.let { client.read(label, it) } ?: LoginResets(label, refusal = NO_TOKEN)
    }

    override suspend fun reset(label: String, kind: String): LoginResetSpend? {
        val login = login(label) ?: return null
        val token = if (kind in ResetKinds.all) token(login) else null
        val spend = when {
            kind !in ResetKinds.all -> LoginResetSpend(label, kind, RESET_UNKNOWN_KIND, NO_SUCH_KIND)
            token == null -> LoginResetSpend(label, kind, "refused", NO_TOKEN)
            else -> client.spend(label, token, kind).also {
                log(
                    "[${LogSafe.str(head)}][resets] ${LogSafe.str(label)} was asked to spend a " +
                        "${LogSafe.str(ResetKinds.name(kind))}: ${LogSafe.str(it.outcome)}\n",
                )
            }
        }
        return if (spend.outcome == RESET_SPENT) spend.copy(readAgain = reads.readAgain(label)) else spend
    }

    private fun claude(): List<WiredAccount> = logins.current().filter { it.extraHeaders === ClaudeLoginHeaders }

    private fun login(label: String): WiredAccount? = claude().firstOrNull { it.label == label }

    private suspend fun token(login: WiredAccount): String? = (login.auth.credentials() as? Credentials.Bearer)?.token
}
