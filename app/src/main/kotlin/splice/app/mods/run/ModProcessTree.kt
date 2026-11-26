package splice.app.mods.run

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter

private const val TERM_GRACE_MS = 1000L // why: allow cleanup after TERM before using KILL
private const val KILL_GRACE_MS = 1000L // why: bound the time spent waiting for a killed root

/** Hold handles across EOF so children orphaned during a cooperative stop are still reaped. */
internal class ModProcessTree(private val root: ProcessHandle, private val waiter: Waiter = ProcessWaiter()) {
    private val descendants = linkedSetOf<ProcessHandle>()

    fun capture() {
        root.descendants().use { stream -> stream.forEach { descendants.add(it) } }
    }

    suspend fun terminate() {
        capture()
        signal(force = false)
        if (root.isAlive || descendants.any { it.isAlive }) waiter.wait(TERM_GRACE_MS)
        capture()
        signal(force = true)
        if (root.isAlive) withTimeoutOrNull(KILL_GRACE_MS) { root.onExit().await() }
    }

    private fun signal(force: Boolean) {
        for (handle in descendants.toList().asReversed() + root) {
            if (handle.isAlive) {
                if (force) handle.destroyForcibly() else handle.destroy()
            }
        }
    }
}
