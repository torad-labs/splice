// NEW: account mutations publish into the head they belong to, without restarting engines or clearing sessions.
package splice.app.auth.claude

import splice.app.provider.WiredAccount
import splice.app.provider.WiredAccountsRead
import java.util.concurrent.ConcurrentHashMap

internal fun interface ClaudePoolChange {
    fun publish()
    fun withdraw(label: String) = publish()
    fun completeWithdrawal(label: String) = publish()
}

/** What the daemon does when a login joins a head. */
internal fun interface LoginJoined {
    fun joined(head: String)
}

internal class ClaudePoolChanges {
    private val heads = ConcurrentHashMap<String, ClaudePoolChange>()
    private val accounts = ConcurrentHashMap<String, List<WiredAccount>>()

    fun view(head: String, initial: List<WiredAccount>): WiredAccountsRead {
        accounts.putIfAbsent(head, initial.toList())
        return WiredAccountsRead { accounts.getValue(head) }
    }

    /** Called with a head whenever a login joins its pool; the daemon wires what a new login sets off. */
    var onJoined: LoginJoined = LoginJoined {}

    /** The account each head's label last proved, so a login to another account in the same place is a new login. */
    private val proved = ConcurrentHashMap<String, String>()

    /** A login joined [head] when a label is new, or when a place now proves another account than it did: Marcos
     *  signed in to a second account in ~/.claude, the label stayed native:claude, and nothing woke (Oct 12). */
    fun accounts(head: String, current: List<WiredAccount>) {
        val swapped = current.filter { reproved(head, it) }
        val before = accounts.put(head, current.toList())?.map { it.label }?.toSet() ?: return
        if (swapped.isNotEmpty() || current.any { it.label !in before }) onJoined.joined(head)
    }

    /** Whether [account] proves another account than its label last did, remembering the one it proves now. A
     *  credential not yet proved says nothing: a token refresh is unproved for a moment and then proves the same one. */
    private fun reproved(head: String, account: WiredAccount): Boolean {
        val now = account.credential.account ?: return false
        val previous = proved.put("$head:${account.label}", now)
        return previous != null && previous != now
    }

    fun bind(head: String, change: ClaudePoolChange) {
        heads[head] = change
    }

    fun withdraw(head: String, label: String) {
        heads[head]?.withdraw(label)
    }

    fun completeWithdrawal(head: String, label: String) {
        heads[head]?.completeWithdrawal(label)
    }

    fun publish(head: String): Boolean {
        val change = heads[head] ?: return false
        change.publish()
        return true
    }
}
