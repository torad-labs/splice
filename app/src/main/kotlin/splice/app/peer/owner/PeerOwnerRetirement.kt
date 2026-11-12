package splice.app.peer.owner

import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerStore

/** A native owner's death retires its registration, so a detached helper is never resurrected for a dead harness. */
internal class PeerOwnerRetirement(private val store: PeerStore, private val log: LogSink) {
    private val processes = PeerOwnerProcesses()

    suspend fun retired(name: String): Boolean {
        val owner = store.nativeOwners[name] ?: return false
        if (processes.live(owner)) return false
        val retired = store.revoke(name, expectedOwner = owner)
        if (retired) log("[peers] ${LogSafe.str(name)}: its native owner ended, so the peer is revoked\n")
        return retired
    }
}
