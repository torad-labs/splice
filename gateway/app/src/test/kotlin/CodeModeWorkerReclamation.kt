// NEW: timeout cleanup is checked independently of another JVM's cold-start latency.
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import splice.app.codemode.JvmCodeModeRuntime
import java.util.concurrent.Semaphore

internal class CodeModeWorkerReclamation(scope: CoroutineScope) {
    private val before = children().map { it.pid() }.toSet()
    private val worker = scope.async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(5_000) {
            var child: ProcessHandle? = null
            while (child == null) {
                child = children().firstOrNull { it.pid() !in before }
                if (child == null) delay(1)
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
        withTimeoutOrNull(5_000) {
            while (child.isAlive || permits.availablePermits() == 0) delay(1)
        }
        assertFalse(child.isAlive, "timed-out worker must be reaped")
        assertEquals(1, permits.availablePermits(), "the single worker slot must be released exactly once")
    }

    private fun children(): List<ProcessHandle> = ProcessHandle.current().children().use { it.toList() }
}
