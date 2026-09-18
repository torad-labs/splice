// NEW: V4-107 — the cancellation handler's quiet close is total: it destroys the child and closes
// its streams without the blocking process-exit wait that only the lifecycle-owning close() may do.
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.codemode.WorkerChannel
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class WorkerChannelCloseQuietlyTest {

    @Test
    fun `the quiet close destroys and closes streams without a blocking exit wait`() {
        val process = RecordingProcess()
        val channel = WorkerChannel(process = process, ioDispatcher = Dispatchers.IO, timeoutMs = 1_000)

        channel.closeQuietly()

        assertTrue(process.destroyed, "the worker process must be destroyed")
        assertFalse(process.waitTimed, "closeQuietly must not block on the process-exit wait")
    }

    private class RecordingProcess : Process() {
        var destroyed = false
        var waitTimed = false
        private val exit = CompletableFuture<Process>()

        override fun onExit(): CompletableFuture<Process> = exit

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitTimed = true
            return true
        }

        override fun exitValue(): Int = 0

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = true
    }
}
