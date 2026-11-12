// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): how the daemon's supervisor tells a codex peer whose
// thread ended from a helper that died. A helper whose thread closed, unloaded or lost its app-server journals `ended`
// and stops; the supervisor then revokes the peer instead of starting its helper again, unless the thread attached
// again after the end. The journal stays, as a revoke leaves it.
package splice.app.peer.codex

import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.sessions.peer.codex.CodexLedger
import splice.sessions.peer.helper.PeerStateFiles
import splice.sessions.peer.journal.PeerJournal
import splice.sessions.peer.store.PeerStore
import java.nio.file.Path

internal class CodexEnds(private val store: PeerStore, private val stateDir: Path, private val log: LogSink) {
    /** Revokes [name] when it is a codex peer whose helper journaled, at or after [since], that its thread ended, and
     *  no attach came after that end. True when it did. */
    suspend fun retired(name: String, since: Long): Boolean {
        if (store.codexThreads[name] == null) return false
        val ended = CodexLedger(PeerJournal(PeerStateFiles(stateDir, name).dir, log)).endedAt()?.takeIf { it >= since }
        val revoked = ended != null && store.revoke(name, threadEndedAt = ended)
        if (revoked) log("[peers] ${LogSafe.str(name)}: its Codex thread ended, so the peer is revoked\n")
        return revoked
    }
}
