// NEW: account mutations publish into the head they belong to, without restarting engines or clearing sessions.
package splice.app.auth.claude

import splice.app.provider.WiredAccount
import splice.app.provider.WiredAccountsRead
import java.util.concurrent.ConcurrentHashMap

internal fun interface ClaudePoolChange {
    fun publish()
    fun withdraw(label: String) = publish()
}

internal class ClaudePoolChanges {
    private val heads = ConcurrentHashMap<String, ClaudePoolChange>()
    private val accounts = ConcurrentHashMap<String, List<WiredAccount>>()

    fun view(head: String, initial: List<WiredAccount>): WiredAccountsRead {
        accounts.putIfAbsent(head, initial.toList())
        return WiredAccountsRead { accounts.getValue(head) }
    }

    fun accounts(head: String, current: List<WiredAccount>) {
        accounts[head] = current.toList()
    }

    fun bind(head: String, change: ClaudePoolChange) {
        heads[head] = change
    }

    fun withdraw(head: String, label: String) {
        heads[head]?.withdraw(label)
    }

    fun publish(head: String): Boolean {
        val change = heads[head] ?: return false
        change.publish()
        return true
    }
}
