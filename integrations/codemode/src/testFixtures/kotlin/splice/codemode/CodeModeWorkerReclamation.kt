// NEW: timeout cleanup is checked independently of another JVM's cold-start latency. A test FIXTURE
// (LAYOUT-01): this module's runtime tests and :app's bridge-over-runtime test both assert with it.
package splice.codemode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import java.util.concurrent.Semaphore

class CodeModeWorkerReclamation(scope: CoroutineScope) {
    private val pollMs = 1L
    private val before = children().map { it.pid() }.toSet()
    private val worker = scope.async(start = CoroutineStart.UNDISPATCHED) {
        // A deadline poll: nothing signals that a child process was spawned.
        withTimeout(5_000) {
            var child: ProcessHandle? = null
            while (child == null) {
                child = children().firstOrNull { it.pid() !in before }
                if (child == null) delay(pollMs)
            }
            child
        }
    }

    suspend fun assertReclaimed(runtime: JvmCodeModeRuntime) {
        val child = worker.await()
        // Capacity stands in for reacquisition; normal-deadline tests cover real replacement boots.
        val field = JvmCodeModeRuntime::class.java.getDeclaredField("permits")
        field.isAccessible = true
        val permits = field.get(runtime) as Semaphore
        // The reap is awaited on the child's own exit future (V4-139); the permit has no event, so
        // its release after the reap is a deadline poll.
        withTimeoutOrNull(5_000) {
            child.onExit().await()
            while (permits.availablePermits() == 0) delay(pollMs)
        }
        assertFalse(child.isAlive, "timed-out worker must be reaped")
        assertEquals(1, permits.availablePermits(), "the single worker slot must be released exactly once")
    }

    private fun children(): List<ProcessHandle> = ProcessHandle.current().children().use { it.toList() }
}
