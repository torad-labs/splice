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

    fun accounts(head: String, current: List<WiredAccount>) {
        val before = accounts.put(head, current.toList())?.map { it.label }?.toSet() ?: return
        if (current.any { it.label !in before }) onJoined.joined(head)
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
