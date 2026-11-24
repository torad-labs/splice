package splice.sessions.peer.store

import splice.core.mcp.McpHarness

/** Separates a peer's inbound inbox from the socket that journals its send receipts. */
public class PeerInboxRouting internal constructor(private val store: PeerStore) {
    /** Claude's native inbox does not write Splice's journal; its Splice sends use a separate return socket. */
    public fun usesReturnHelper(name: String): Boolean =
        name in store.nativeInboxes && store.harnesses[name] == McpHarness.CLAUDE.definition.wire

    public fun runsHelper(name: String): Boolean = name !in store.nativeInboxes || usesReturnHelper(name)
}
