// NEW: context disposal releases its pending replies without ending the shared host.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SharedWorkerChannelCellCloseTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `closing one cell ends its withheld exchange and accepts its late reply`(late: Boolean) = runBlocking {
        val process = SyntheticHost()
        val host = SharedWorkerChannel(process, this)
        host.awaitReady()
        val first = host.cell(1)
        val firstExited = CompletableDeferred<Unit>()
        first.afterExit { firstExited.complete(Unit) }
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
            withTimeout(1_000) { firstExited.await() }
            assertEquals(0, pendingCount(host), "a disposed cell must not retain its cancelled reply slot")
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

    @Test
    fun `cancelling an exchange releases its slot while the cell and host stay healthy`() = runBlocking {
        val process = SyntheticHost()
        SharedWorkerChannel(process, this).use { host ->
            host.awaitReady()
            val cell = host.cell(1)
            val waiting = async { cell.exchange(HostProtocol.command("synthetic-work")) }
            try {
                val request = withTimeout(1_000) { process.sent.receive() }
                waiting.cancelAndJoin()
                assertEquals(0, pendingCount(host), "cancellation alone must release the reply slot")
                process.reply(request, CodeModeWire.completedFrame("late", null))
                val next = async { cell.exchange(HostProtocol.command("synthetic-work")) }
                val nextRequest = withTimeout(1_000) { process.sent.receive() }
                process.reply(nextRequest, CodeModeWire.completedFrame("healthy", null))
                withTimeout(1_000) { next.await() }
                assertEquals(0, pendingCount(host))
                assertFalse(host.isClosed)
            } finally {
                waiting.cancelAndJoin()
                cell.close()
            }
        }
    }

    @Test
    fun `an authenticated late task death closes the generation without a retained caller`() = runBlocking {
        val process = SyntheticHost()
        SharedWorkerChannel(process, this).use { host ->
            host.awaitReady()
            val gone = CompletableDeferred<Unit>()
            host.afterExit { gone.complete(Unit) }
            val cell = host.cell(1)
            val waiting = async { cell.exchange(HostProtocol.command("synthetic-work")) }
            try {
                val request = withTimeout(1_000) { process.sent.receive() }
                waiting.cancelAndJoin()
                assertEquals(0, pendingCount(host))
                val died = InternalError("synthetic").apply { stackTrace = emptyArray() }
                process.reply(request, CodeModeFatalFrame.died(died, CodeModeWire.completedFrame("late", null)))
                withTimeout(1_000) { gone.await() }
                assertTrue(host.isClosed)
                assertEquals("InternalError in unknown.frame", host.death)
            } finally {
                waiting.cancelAndJoin()
                cell.close()
            }
        }
    }

    @Test
    fun `a cancelled slot cannot claim a fatal reply through a stale recipient lookup`() = runBlocking {
        val process = SyntheticHost()
        val parent = CoroutineScope(coroutineContext + Dispatchers.Default)
        SharedWorkerChannel(process, parent).use { host ->
            host.awaitReady()
            val replies = CancellationRaceReplies()
            val field = SharedWorkerChannel::class.java.getDeclaredField("pending").apply { isAccessible = true }
            field.set(host, replies)
            val gone = CompletableDeferred<Unit>()
            host.afterExit {
                // Exit is published before the held cancellation is released: once remove() returns, the
                // assertion below must see it, with no window between the latch and the completion.
                gone.complete(Unit)
                replies.retired.countDown()
            }
            val cell = host.cell(1)
            val waiting = async(Dispatchers.Default) { cell.exchange(HostProtocol.command("synthetic-work")) }
            try {
                val request = withTimeout(1_000) { process.sent.receive() }
                replies.holdLookup = true
                val died = InternalError("synthetic").apply { stackTrace = emptyArray() }
                process.reply(request, CodeModeFatalFrame.died(died, CodeModeWire.completedFrame("late", null)))
                assertTrue(replies.lookedUp.await(2, TimeUnit.SECONDS))
                waiting.cancelAndJoin()
                assertTrue(gone.isCompleted, "a removed caller cannot claim a fatal reply from a stale lookup")
                assertEquals(0, pendingCount(host))
            } finally {
                waiting.cancelAndJoin()
                cell.close()
            }
        }
    }

    @Test
    fun `a fatal control reply without an owed result wakes its waiter before the control deadline`() = runBlocking {
        val process = SyntheticHost()
        SharedWorkerChannel(process, this).use { host ->
            host.awaitReady()
            val waiting = async {
                try {
                    host.control(1, HostProtocol.command("synthetic-control"), timeoutMs = 2_000)
                    null
                } catch (error: java.io.IOException) {
                    error
                }
            }
            val request = withTimeout(1_000) { process.sent.receive() }
            val died = InternalError("synthetic").apply { stackTrace = emptyArray() }
            process.reply(request, CodeModeFatalFrame.died(died))
            val failure = withTimeout(500) { waiting.await() }
            assertTrue(failure is CodeModeWorkerLostException, "fatal processing cannot strand a taken control slot")
            assertEquals(0, pendingCount(host))
        }
    }

    private class CancellationRaceReplies : ConcurrentHashMap<Long, Any>() {
        val lookedUp = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val retired = CountDownLatch(1)

        @Volatile var holdLookup = false

        override fun get(key: Long): Any? {
            val reply = super.get(key)
            if (holdLookup && reply != null) {
                lookedUp.countDown()
                check(removed.await(2, TimeUnit.SECONDS))
            }
            return reply
        }

        override fun remove(key: Long): Any? {
            val reply = super.remove(key)
            if (holdLookup && reply != null) {
                removed.countDown()
                // Hold cancellation before answer.cancel, so a stale lookup would falsely complete the answer.
                retired.await(2, TimeUnit.SECONDS)
            }
            return reply
        }
    }

    private fun pendingCount(host: SharedWorkerChannel): Int {
        val field = SharedWorkerChannel::class.java.getDeclaredField("pending").apply { isAccessible = true }
        return (field.get(host) as Map<*, *>).size
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
            CodeModeWire.write(replies, HostProtocol.reply(request, payload))
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
