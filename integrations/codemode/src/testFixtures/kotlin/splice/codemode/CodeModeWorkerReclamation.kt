// NEW: host-exit fixture observes its spawned process, never an unrelated child or a script permit.
package splice.codemode

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import java.util.concurrent.CompletableFuture

class CodeModeWorkerReclamation : WorkerSpawn {
    private val worker = CompletableFuture<Process>()

    override fun invoke(builder: ProcessBuilder): Process = builder.start().also { worker.complete(it) }

    suspend fun assertReclaimed(runtime: JvmCodeModeRuntime) {
        runtime.close()
        val process = withTimeout(5_000) { worker.await() }
        withTimeout(5_000) { process.onExit().await() }
        assertFalse(process.isAlive, "the closed host must be reaped")
    }
}
