package splice.head.v4349

import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.head.wire.ClientChannel
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.LostClient
import splice.upstream.Ticker
import java.util.concurrent.atomic.AtomicBoolean

class ClientKeepaliveFailureTest {
    @Test
    fun `closed keepalive channel says which connection closed without a class-name headline`() = runBlocking {
        val logs = mutableListOf<String>()
        val turn = Job()
        val channel = ClientChannel(
            ImmediateSseWriter(writeRaw = { throw ClosedWriteChannelException() }, flushRaw = {}),
            Mutex(),
            AtomicBoolean(false),
        )
        val once = Ticker { false }
        val ticker = object : Ticker {
            var emitted = false
            override suspend fun awaitTick(intervalMs: Long): Boolean =
                if (emitted) once.awaitTick(intervalMs) else true.also { emitted = true }
        }
        channel.launchClientPinger(this, turn, ticker, LostClient("muse", { logs += it })).join()
        val line = logs.single()
        assertTrue(line.contains("client connection closed while writing a keepalive"), line)
        assertFalse(line.contains("ClosedWriteChannelException"), line)
        assertTrue(channel.clientGone.get())
        assertTrue(turn.isCancelled)
    }
}
