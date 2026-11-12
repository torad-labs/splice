package splice.app.peer.owner

import kotlinx.coroutines.future.await
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.PeerOwnerProcesses

/** The helper owns this cancellable process-exit wait; a daemon restart has no bearing on it. */
internal class PeerOwnerWatch {
    private val processes = PeerOwnerProcesses()

    suspend fun await(owner: NativeProcess) {
        val process = ProcessHandle.of(owner.pid).orElse(null) ?: return
        if (!processes.live(owner)) return
        process.onExit().await()
    }
}
