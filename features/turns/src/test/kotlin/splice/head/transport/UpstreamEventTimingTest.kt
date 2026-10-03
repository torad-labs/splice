// NEW: V4-456 — reader tails, semantic event kinds and ping-only content silence.
package splice.head.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.UpstreamGapEnd
import java.net.SocketException

@OptIn(ExperimentalCoroutinesApi::class)
class UpstreamEventTimingTest {
    private val text = Json.parseToJsonElement(
        """{"type":"content_block_delta","delta":{"type":"text_delta","text":"synthetic"}}""",
    ).jsonObject
    private val ping = Json.parseToJsonElement("""{"type":"ping"}""").jsonObject

    @ParameterizedTest
    @ValueSource(strings = ["completed", "torn", "cancelled"])
    fun `terminal silence is measured with the actual ending kind`(ending: String) = runTest {
        val perf = TurnPerf { testScheduler.currentTime }
        val cancellation = CancellationException("synthetic cancellation")
        val events = flow {
            emit(text)
            delay(40_000)
            when (ending) {
                "torn" -> throw SocketException("synthetic reset")
                "cancelled" -> throw cancellation
            }
        }
        var failure: Throwable? = null
        try {
            UpstreamEventTiming(perf, 0).observe(events).toList()
        } catch (cancelled: CancellationException) {
            failure = cancelled
        } catch (torn: SocketException) {
            failure = torn
        }
        when (ending) {
            "cancelled" -> assertSame(cancellation, failure)
            "torn" -> assertInstanceOf(SocketException::class.java, failure)
            else -> assertNull(failure)
        }
        assertEquals(40_000L, perf.snapshot().counters[PerfKeys.UP_GAP_MAX_MS])
        assertEquals(1L, perf.snapshot().counters[PerfKeys.UP_GAPS_2S])
        assertEquals(ending, perf.snapshot().upstreamGapEnd?.wire)
        assertEquals(40_000L, perf.snapshot().counters[PerfKeys.UP_CONTENT_GAP_MAX_MS])
    }

    @ParameterizedTest
    @ValueSource(longs = [0, 500])
    fun `ping delivery does not reset content silence or charge downstream time`(deliveryMs: Long) = runTest {
        val perf = TurnPerf { testScheduler.currentTime }
        val events = flow {
            emit(text)
            repeat(30) {
                delay(1_000)
                emit(ping)
            }
            emit(text)
        }
        UpstreamEventTiming(perf, 0).observe(events).collect { delay(deliveryMs) }
        assertEquals(1_000L, perf.snapshot().counters[PerfKeys.UP_GAP_MAX_MS])
        assertEquals(30_000L, perf.snapshot().counters[PerfKeys.UP_CONTENT_GAP_MAX_MS])
        assertEquals(deliveryMs, perf.snapshot().counters[PerfKeys.UP_BLOCKED_MAX_MS])
    }

    @Test
    fun `ping-only and empty-delta rounds expose their full content wait`() = runTest {
        for (nonContent in listOf(
            ping,
            Json.parseToJsonElement(
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":""}}""",
            ).jsonObject,
        )) {
            val startMs = testScheduler.currentTime
            val perf = TurnPerf { testScheduler.currentTime }
            val events = flow {
                repeat(30) {
                    delay(1_000)
                    emit(nonContent)
                }
            }
            UpstreamEventTiming(perf, 0).observe(events).toList()
            assertEquals(30_000L, perf.snapshot().counters[PerfKeys.UP_CONTENT_GAP_MAX_MS])
            assertEquals(startMs + 30_000, testScheduler.currentTime)
        }
    }

    @Test
    fun `role openings with null or empty content report message start`() = runTest {
        for (raw in listOf(
            """{"choices":[{"delta":{"role":"assistant","content":null}}]}""",
            """{"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            """{"choices":[{"delta":{"role":"assistant"}}]}""",
        )) {
            val perf = TurnPerf { testScheduler.currentTime }
            val events = flow {
                delay(9_000)
                emit(Json.parseToJsonElement(raw).jsonObject)
            }
            UpstreamEventTiming(perf, 0).observe(events).toList()
            assertEquals(UpstreamGapEnd.MESSAGE_START, perf.snapshot().upstreamGapEnd, raw)
        }
    }

    @Test
    fun `empty chat fields never hide content in another field or choice`() = runTest {
        for ((raw, kind) in listOf(
            """{"choices":[{"delta":{},"message":{"content":"synthetic"},"finish_reason":"stop"}]}""" to
                UpstreamGapEnd.TEXT_DELTA,
            """{"choices":[{"delta":{},"message":{"reasoning_content":"synthetic"},"finish_reason":"stop"}]}""" to
                UpstreamGapEnd.THINKING_DELTA,
            """{"choices":[{"delta":{"reasoning_content":null,"content":"synthetic"}}]}""" to UpstreamGapEnd.TEXT_DELTA,
            """{"choices":[{"delta":{"reasoning_content":"","content":"synthetic"}}]}""" to UpstreamGapEnd.TEXT_DELTA,
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":""}}],"content":"synthetic"}}]}""" to
                UpstreamGapEnd.TEXT_DELTA,
            """{"choices":[{"delta":{"role":"assistant"}},{"delta":{"content":"synthetic"}}]}""" to UpstreamGapEnd.TEXT_DELTA,
            """{"choices":[{"delta":{"reasoning_content":null,"tool_calls":[{"function":{"name":"synthetic"}}]}}]}""" to
                UpstreamGapEnd.CONTENT_BLOCK_START,
        )) {
            val perf = TurnPerf { testScheduler.currentTime }
            val events = flow {
                repeat(30) {
                    delay(1_000)
                    emit(Json.parseToJsonElement(raw).jsonObject)
                }
            }
            UpstreamEventTiming(perf, 0).observe(events).toList()
            assertEquals(1_000L, perf.snapshot().counters[PerfKeys.UP_CONTENT_GAP_MAX_MS], raw)
            assertEquals(kind, perf.snapshot().upstreamGapEnd, raw)
        }
    }

    @Test
    fun `done-only Responses payloads end content silence`() = runTest {
        for (raw in listOf(
            """{"type":"response.done","response":{"output":[{"type":"message","content":[{"text":"synthetic"}]}]}}""",
            """{"type":"response.function_call_arguments.done","output_index":0,"arguments":"{}"}""",
            """{"type":"response.output_item.done","item":{"type":"reasoning","summary":[{"text":"synthetic"}]}}""",
            """{"type":"response.completed","response":{"output":[{"type":"message","content":[{"text":"synthetic"}]}]}}""",
        )) {
            val perf = TurnPerf { testScheduler.currentTime }
            val events = flow {
                emit(text)
                delay(10_000)
                emit(Json.parseToJsonElement(raw).jsonObject)
                delay(10_000)
            }
            UpstreamEventTiming(perf, 0).observe(events).toList()
            assertEquals(10_000L, perf.snapshot().counters[PerfKeys.UP_CONTENT_GAP_MAX_MS], raw)
        }
    }

    @Test
    fun `empty structural Responses items do not fabricate content progress`() = runTest {
        for (raw in listOf(
            """{"type":"response.output_item.added","item":{"type":"reasoning","summary":[]}}""",
            """{"type":"response.output_item.added","item":{"type":"message","content":[]}}""",
            """{"type":"response.content_part.added","part":{"type":"output_text","text":""}}""",
            """{"type":"response.reasoning_summary_part.added","part":{"type":"summary_text","text":""}}""",
        )) {
            val perf = TurnPerf { testScheduler.currentTime }
            val events = flow {
                repeat(30) {
                    delay(1_000)
                    emit(Json.parseToJsonElement(raw).jsonObject)
                }
            }
            UpstreamEventTiming(perf, 0).observe(events).toList()
            assertEquals(30_000L, perf.snapshot().counters[PerfKeys.UP_CONTENT_GAP_MAX_MS], raw)
        }
    }

    @Test
    fun `served Responses and chat events carry semantic kinds`() = runTest {
        for ((raw, expected) in listOf(
            """{"type":"response.done","response":{"output":[{"type":"message","content":[{"text":"synthetic"}]}]}}""" to
                UpstreamGapEnd.COMPLETED,
            """{"type":"response.reasoning_summary_text.delta","delta":"synthetic"}""" to UpstreamGapEnd.THINKING_DELTA,
            """{"type":"response.reasoning_text.delta","delta":"synthetic"}""" to UpstreamGapEnd.THINKING_DELTA,
            """{"type":"response.output_text.delta","delta":"synthetic"}""" to UpstreamGapEnd.TEXT_DELTA,
            """{"type":"response.function_call_arguments.delta","delta":"{}"}""" to UpstreamGapEnd.INPUT_JSON_DELTA,
            """{"type":"response.output_item.added","item":{"type":"function_call"}}""" to UpstreamGapEnd.CONTENT_BLOCK_START,
            """{"choices":[{"delta":{"reasoning_content":"synthetic"}}]}""" to UpstreamGapEnd.THINKING_DELTA,
            """{"choices":[{"delta":{"reasoning":"synthetic"}}]}""" to UpstreamGapEnd.THINKING_DELTA,
            """{"choices":[{"delta":{"content":"synthetic"}}]}""" to UpstreamGapEnd.TEXT_DELTA,
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{}"}}]}}]}""" to UpstreamGapEnd.INPUT_JSON_DELTA,
            """{"choices":[{"delta":{"tool_calls":[{"function":{"name":"synthetic"}}]}}]}""" to UpstreamGapEnd.CONTENT_BLOCK_START,
            """{"type":"response.created"}""" to UpstreamGapEnd.MESSAGE_START,
            """{"type":"unrecognized","delta":"synthetic"}""" to UpstreamGapEnd.UNKNOWN,
        )) {
            val perf = TurnPerf { testScheduler.currentTime }
            val events = flow {
                delay(2_500)
                emit(Json.parseToJsonElement(raw).jsonObject)
            }
            UpstreamEventTiming(perf, 0).observe(events).toList()
            assertEquals(expected, perf.snapshot().upstreamGapEnd, raw)
        }
    }
}
