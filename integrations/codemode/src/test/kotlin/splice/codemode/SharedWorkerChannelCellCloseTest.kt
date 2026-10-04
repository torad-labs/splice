// NEW: context disposal releases its pending replies without ending the shared host.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SharedWorkerChannelCellCloseTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `closing one cell ends its withheld exchange and accepts its late reply`(late: Boolean) = runBlocking {
        val process = SyntheticHost()
        val host = SharedWorkerChannel(process, this)
        host.awaitReady()
        val first = host.cell(1)
        val sibling = host.cell(2)
        val waiting = async {
            try {
                first.exchange(HostProtocol.command("synthetic-work"))
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: CodeModeWorkerLostException) {
                error
            }
        }
        try {
            val request = withTimeout(1_000) { process.sent.receive() }
            first.close()
            val close = withTimeout(1_000) { process.sent.receive() }
            val failure = withTimeout(500) { waiting.await() }
            assertTrue(failure is CodeModeWorkerLostException, "context close must release the pending reply")
            process.reply(close, CodeModeWire.completedFrame("", null))
            if (late) process.reply(request, CodeModeWire.completedFrame("late", null))
            val healthy = async { sibling.exchange(HostProtocol.command("synthetic-work")) }
            val next = withTimeout(1_000) { process.sent.receive() }
            process.reply(next, CodeModeWire.completedFrame("sibling", null))
            withTimeout(1_000) { healthy.await() }
            assertFalse(host.isClosed, "a late reply for the disposed context cannot kill the sibling")
        } finally {
            waiting.cancelAndJoin()
            first.close()
            sibling.close()
            host.close()
        }
    }

    private class SyntheticHost : Process() {
        val sent = Channel<HostFrame>(Channel.UNLIMITED)
        private val stdout = PipedInputStream(65_536)
        private val replies = DataOutputStream(PipedOutputStream(stdout))
        private val exit = CompletableFuture<Process>()
        private var alive = true
        private val stdin = object : ByteArrayOutputStream() {
            override fun flush() {
                if (size() == 0) return
                val frame = CodeModeWire.read(DataInputStream(ByteArrayInputStream(toByteArray())))
                reset()
                check(sent.trySend(HostProtocol.parse(frame)).isSuccess)
            }
        }

        init {
            CodeModeWire.write(replies, CodeModeWire.readyFrame())
        }

        fun reply(request: HostFrame, payload: JsonObject) {
            CodeModeWire.write(replies, HostProtocol.frame(request.cell, request.request, payload, request.session))
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun onExit(): CompletableFuture<Process> = exit
        override fun isAlive(): Boolean = alive
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0

        override fun destroy() {
            alive = false
            replies.close()
            stdout.close()
            exit.complete(this)
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }
}
