// NEW: #525 (2026-11-13). A daemon the launcher starts beside a running one waits for it to hand over, 3.5 s on the
// machine this was measured on, and the running one serves every request meanwhile. After the hand-over each
// command's requests wait for this start, and thread samples of one showed its first second going to work that needs
// neither the lock nor anything the retiring daemon still writes: hashing this jar to pin the code-mode worker's copy
// of it (two samples of four a second), the JVM's management server that HeapFlags asks, and kotlin-reflect's
// built-ins, which the first Ktor plugin a process loads reads. Those are made during the wait instead.
//
// EACH PIECE IS KEPT BY THE PROCESS, NOT BY THIS CLASS: the pin by WorkerArtifacts' lazy, the server by the JVM, the
// plugin by its class. So the start takes a piece that is made, waits for one that is still being made, and makes
// one that failed here itself, where it fails with its own reason as it always did. A start with no daemon to wait
// for gets the lock at once and loses nothing: the pieces run beside it.
package splice.app.daemon

import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import splice.app.DaemonBoundary
import splice.codemode.WorkerArtifacts
import splice.core.util.Cancellables
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.lang.management.ManagementFactory

/** One piece of a start that needs no daemon lock. */
internal fun interface AheadOfLock {
    operator fun invoke()
}

internal class StartAhead(private val scope: CoroutineScope = LifecycleScope(ProcessDispatchers().io())) {
    private val boundary = DaemonBoundary()

    /** Begins each piece on a thread of its own and returns at once. */
    fun begin() {
        ahead { WorkerArtifacts.pinAtBoot() }
        ahead { val _ = ManagementFactory.getPlatformMBeanServer() }
        ahead { val _ = HttpTimeout.key }
    }

    private fun ahead(piece: AheadOfLock) {
        val _ = scope.launch {
            Cancellables.discard(
                boundary.runCatchingDaemonBoundary { piece() },
                "a piece that failed ahead of the lock is made again where the start uses it, and fails there " +
                    "with its reason",
            )
        }
    }
}
