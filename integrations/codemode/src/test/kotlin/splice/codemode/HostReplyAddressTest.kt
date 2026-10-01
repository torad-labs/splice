// NEW: corrupt reply addresses fail the waiting caller instead of silently abandoning it.
package splice.codemode

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HostReplyAddressTest {
    @Test
    fun `an unknown request id fails the caller instead of hanging`() {
        assertAddressFailure(wrongRequest = true)
    }

    @Test
    fun `a wrong cell id fails the caller instead of corrupting sibling results`() {
        assertAddressFailure(wrongRequest = false)
    }

    private fun assertAddressFailure(wrongRequest: Boolean) {
        assertThrows(IOException::class.java) {
            runBlocking {
                JvmCodeModeRuntime(spawn = WorkerSpawn { WrongAddressProcess(wrongRequest) }).use { runtime ->
                    withTimeout(2_000) { runtime.start("return 'never';", emptySet()) }
                }
            }
        }
    }

    private class WrongAddressProcess(private val wrongRequest: Boolean) : Process() {
        private val replies = PipedInputStream()
        private val writer = DataOutputStream(PipedOutputStream(replies))
        private val exited = CompletableFuture<Process>()
        private val requests = object : ByteArrayOutputStream() {
            override fun flush() {
                if (size() == 0) return
                val frame = CodeModeWire.read(DataInputStream(ByteArrayInputStream(toByteArray())))
                val request = HostProtocol.parse(frame)
                reset()
                CodeModeWire.write(
                    writer,
                    HostProtocol.frame(
                        request.cell + if (wrongRequest) 0 else 1,
                        request.request + if (wrongRequest) 1 else 0,
                        CodeModeWire.completedFrame("wrong", null),
                    ),
                )
            }
        }

        init {
            CodeModeWire.write(writer, CodeModeWire.readyFrame())
        }

        override fun getInputStream(): InputStream = replies
        override fun getOutputStream(): OutputStream = requests
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun onExit(): CompletableFuture<Process> = exited
        override fun waitFor(): Int {
            exited.get()
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.isDone
        override fun exitValue(): Int = if (exited.isDone) 0 else throw IllegalThreadStateException()
        override fun isAlive(): Boolean = !exited.isDone
        override fun destroy() {
            writer.close()
            exited.complete(this)
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }
}
