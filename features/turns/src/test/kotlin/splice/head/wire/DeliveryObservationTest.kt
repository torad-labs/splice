package splice.head.wire

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag
import splice.core.perf.TurnPerf
import splice.core.perf.observation.PerfAccumulator
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.head.turn.delivery.CollectedReply
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class DeliveryObservationTest {
    @Test
    fun `heartbeat flush is a byte but meaningful content begins with actual reasoning text and tool flushes`() {
        val rig = Fixture()
        val wire = StringBuilder()
        val channel = rig.channel(wire)
        rig.now = 10
        channel.timedClientWrite("event: ping\ndata: {}\n\n", rig.perf, rig.clock)
        assertEquals(0.0, rig.sum("arrival_to_first_meaningful_content_ms"))
        rig.now = 20
        channel.timedClientWrite(delta("thinking_delta", "thinking", "reasoning"), rig.perf, rig.clock)
        rig.now = 30
        channel.timedClientWrite(delta("text_delta", "text", "answer"), rig.perf, rig.clock)
        rig.now = 40
        channel.timedClientWrite(
            "event: content_block_start\ndata: {\"content_block\":{\"type\":\"tool_use\",\"name\":\"test\"}}\n\n",
            rig.perf,
            rig.clock,
        )
        rig.now = 50
        channel.timedClientWrite("event: message_stop\ndata: {}\n\n", rig.perf, rig.clock)
        rig.request.finish(OutcomeTag.OK.wire)
        assertEquals(15.0, rig.sum("arrival_to_first_client_byte_ms"))
        assertEquals(25.0, rig.sum("arrival_to_first_meaningful_content_ms"))
        assertEquals(25.0, rig.sum("arrival_to_first_reasoning_ms"))
        assertEquals(35.0, rig.sum("arrival_to_first_text_ms"))
        assertEquals(45.0, rig.sum("arrival_to_first_tool_ms"))
        assertEquals(55.0, rig.sum("arrival_to_terminal_ms"))
        assertEquals(1.0, rig.sum("requests_finished"))
        assertEquals(wire.toString().toByteArray().size.toDouble(), rig.sum("downstream_bytes"))
        assertEquals(25.0, rig.sum("client_write_flush_ms"))
    }

    @Test
    fun `failed flush closes as abandoned without inventing delivered content`() {
        val rig = Fixture()
        val channel = ClientChannel(
            ImmediateSseWriter({}, { throw IOException("synthetic flush failure") }),
            Mutex(),
            AtomicBoolean(false),
        )
        assertThrows(IOException::class.java) {
            channel.timedClientWrite(delta("text_delta", "text", "answer"), rig.perf, rig.clock)
        }
        rig.request.finish(OutcomeTag.CANCELLED.wire)
        assertEquals(1.0, rig.sum("requests_finished"))
        assertEquals(0.0, rig.sum("downstream_bytes"))
        assertTrue(rig.accumulator.snapshot().points.none { it.metric.startsWith("arrival_to_first_") })
        assertTrue(channel.clientGone.get())
    }

    @Test
    fun `collected reply observes its content only once its body is flushed`() = runBlocking {
        val rig = Fixture()
        val body = "{\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"answer\"}]}"
        rig.now = 80
        CollectedReply(body, HttpStatusCode.OK, rig.perf).writeTo(ByteChannel())
        assertEquals(80.0, rig.sum("arrival_to_first_text_ms"))
        assertEquals(body.toByteArray().size.toDouble(), rig.sum("downstream_bytes"))
        assertEquals(1.0, rig.sum("requests_good"))
    }

    private fun delta(type: String, key: String, text: String): String =
        "event: content_block_delta\ndata: {\"delta\":{\"type\":\"$type\",\"$key\":\"$text\"}}\n\n"

    private class Fixture {
        var now = 0L
        val clock = ElapsedClock { now }
        private val identity = PerfIdentity("test", "test", "test", "test")
        val accumulator = PerfAccumulator(identity)
        private val recorder = PerfRecorder(identity, accumulator, clock, WallClock { 60_000L + now })
        val request = recorder.request(PerfScope(head = "test", streaming = true), 0L, 60_000L)
        val perf = TurnPerf(observations = request, clock = clock)

        fun channel(wire: StringBuilder): ClientChannel = ClientChannel(
            ImmediateSseWriter({ wire.append(it) }, { now += 5 }),
            Mutex(),
            AtomicBoolean(false),
        )

        fun sum(metric: String): Double = accumulator.snapshot().points.filter { it.metric == metric }.sumOf { it.sum }
    }
}
