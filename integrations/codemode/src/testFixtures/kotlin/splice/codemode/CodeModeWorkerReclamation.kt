// NEW: host-exit fixture observes its spawned process, never an unrelated child or a script permit.
package splice.codemode

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import splice.upstream.codemode.CodeModeRuntime
import java.util.concurrent.CompletableFuture

/** How long a closed host has to exit, and its worker to be seen exiting, before the fixture fails. */
private const val REAP_TIMEOUT_MS = 5_000L

class CodeModeWorkerReclamation : WorkerSpawn {
    private val worker = CompletableFuture<Process>()

    override fun invoke(builder: ProcessBuilder): Process = builder.start().also { worker.complete(it) }

    suspend fun assertReclaimed(runtime: CodeModeRuntime) {
        runtime.close()
        val process = withTimeout(REAP_TIMEOUT_MS) { worker.await() }
        withTimeout(REAP_TIMEOUT_MS) { process.onExit().await() }
        assertFalse(process.isAlive, "the closed host must be reaped")
    }
}
