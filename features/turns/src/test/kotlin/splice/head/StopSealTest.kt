// NEW: the seal wait of a head's drain, driven through a real HeadEngine's call counter. A turn's gate slot is
// released before its response's last chunk is written, so a drain that watched the gate alone could stop the engine
// while a call was still inside its route; this pins the wait itself, with no timing in it.
package splice.head

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.upstream.Waiter
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val HOLD_WAIT_MS = 15_000L
private const val ONE_HOUR_NS = 3_600_000_000_000L

class StopSealTest {
    @Test
    fun `the seal wait holds while a call is inside a route with the gate empty, and ends when it leaves`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val deps = headDeps(tmp, log = {})
        val engine = dispatchEngine(dispatchProvider("http://127.0.0.1:1"), deps)
        val dispatcher = HeldCallDispatcher(Dispatchers.IO)
        // Each poll of the wait takes one tick the test hands it, so the test decides how many times it has looked.
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        engine.start(callThreads = 1, callDispatcher = dispatcher)
        try {
            val reply = async(Dispatchers.IO) {
                Socket("127.0.0.1", engine.port).use { socket ->
                    writePost(socket, engine.port, "seal", path = "/v1/messages/count_tokens")
                    socket.getInputStream().bufferedReader().readText()
                }
            }
            assertTrue(withContext(Dispatchers.IO) { dispatcher.entered.await(HOLD_WAIT_MS, TimeUnit.MILLISECONDS) })
            assertEquals(1, engine.activeCalls, "the call is inside its route handler")
            assertEquals(0, deps.traffic.gate.snapshot().inflight, "and holds no gate slot")

            val polls = Channel<Unit>(Channel.UNLIMITED)
            val seal = StopSeal(deps.traffic.gate, engine, Waiter { polls.send(Unit); ticks.receive() })
            val settling = async { seal.settle(System.nanoTime() + ONE_HOUR_NS) }
            repeat(3) {
                polls.receive()
                ticks.send(Unit)
            }
            yield()
            assertFalse(settling.isCompleted, "the wait must not end while a call is still inside a route")

            dispatcher.release.countDown()
            withTimeout(HOLD_WAIT_MS) {
                while (!settling.isCompleted) {
                    ticks.trySend(Unit)
                    yield()
                }
            }
            assertTrue(reply.await().contains("input_tokens"), "the held call was answered whole")
            assertEquals(0, engine.activeCalls)
        } finally {
            dispatcher.release.countDown()
            engine.stop()
            assertTrue(AsyncFileIo.drain())
        }
    }
}
