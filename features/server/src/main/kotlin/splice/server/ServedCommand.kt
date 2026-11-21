// NEW: gateway-no-heads. What a turn's admission, its person's allowlist and its ledger row need to know of the command
// it is on, and nothing of how it runs: who it is, the names a grant may call it by, and whether it runs. A head's
// command (HeadCommand) and a provider the team door selected (ProviderCommand) are both one, so admission carries
// neither a head's port and launch nor a stand-in for them.
package splice.server

import splice.share.SharedHead

internal interface ServedCommand {
    /** The key its ledger rows and upstream pool are asked by: the head's key, or the provider's. */
    val key: String

    /** The name its refusals and its row say. */
    val name: String

    /** The names a grant (`[server] commands`, a person's or team's `--commands`) may call it by. */
    val callableAs: Set<String>

    fun running(): Boolean
}

/** A served head, as a command: called by its command or its key. */
internal class HeadCommand(private val head: SharedHead) : ServedCommand {
    override val key: String get() = head.key
    override val name: String get() = head.command
    override val callableAs: Set<String> get() = setOf(head.command, head.key)

    override fun running(): Boolean = head.running()
}

/** A provider the team door selected, as a command: called by [callableAs], which is every configured name that
 *  resolves to this provider alone (TeamNames), and always running, since it has no process of its own to be down. */
internal class ProviderCommand(override val key: String, override val callableAs: Set<String>) : ServedCommand {
    override val name: String get() = key

    override fun running(): Boolean = true
}
