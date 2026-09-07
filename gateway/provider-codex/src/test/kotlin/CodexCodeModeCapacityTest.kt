import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodexCodeModeBridge
import splice.spi.CodeModeCall
import splice.spi.CodeModeCapacityException
import splice.spi.CodeModeCell
import splice.spi.CodeModeResult
import splice.spi.CodeModeRuntime
import splice.spi.CodeModeStep
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/**
 * Worker slots are the scarce resource. On 2026-09-07 four parked cells (clients that never
 * returned results) held all four slots for an hour, and every new script on every session failed
 * with "runtime failed to start" — a 502 the client retried identically. These pin: a parked cell
 * is reaped after the idle timeout, evicted at capacity, and when nothing can be evicted the model
 * hears about it in the script's own output instead of the turn failing.
 */
class CodexCodeModeCapacityTest : CodeModeBridgeTestSupport() {
    @Test
    fun `capacity with every cell busy reports to the model and the turn continues`() = runTest {
        val runtime = BoundedRuntime(capacity = 1)
        val manager = bridge(runtime)
        manager.interceptor(turn(sessionId = "session-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-a") }
        assertEquals(1, runtime.open)

        var posted = ""
        var posts = 0
        val outcome = manager.interceptor(turn(sessionId = "session-b"), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { body ->
                posted = body
                if (posts++ == 0) outerOutcome("outer-b") else completedOutcome()
            }

        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals(1, runtime.open)
        val output = Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray
            .map { it.jsonObject }
            .single { item ->
                item["call_id"]?.jsonPrimitive?.content == "outer-b" &&
                    item.getValue("type").jsonPrimitive.content == "custom_tool_call_output"
            }
            .getValue("output").jsonPrimitive.content
        assertTrue("capacity reached" in output, output)
        assertTrue("\"sourceRerun\":false" in output)
        assertTrue(logLines.any { "capacity reached" in it })
    }

    @Test
    fun `capacity evicts the oldest parked cell past the eviction floor`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = BoundedRuntime(capacity = 1)
        val manager = bridge(runtime, clock = clock)
        manager.interceptor(turn(sessionId = "session-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-a") }
        val parked = runtime.cells.single()
        clock.now += 3.minutes.inWholeMilliseconds

        val sink = RecordingSink()
        val outcome = manager.interceptor(turn(sessionId = "session-b"), outer("outer-b"), disableParallel = false)
            .intercept(BASE_REQUEST, sink) { outerOutcome("outer-b") }

        assertTrue(outcome is TurnOutcome.Success)
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertTrue(parked.closed)
        assertEquals(2, runtime.starts)
        assertEquals(1, runtime.open)
        assertEquals("LOST", phaseOf("outer-a"))
        assertTrue(logLines.any { "evicted" in it && "outer-a" in it })
    }

    @Test
    fun `a cell parked past the idle timeout is reaped on the next code-mode turn`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = BoundedRuntime(capacity = 4)
        val manager = bridge(runtime, clock = clock)
        manager.interceptor(turn(sessionId = "session-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-a") }
        val parked = runtime.cells.single()
        clock.now += 31.minutes.inWholeMilliseconds

        manager.interceptor(turn(sessionId = "session-b"), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }

        assertTrue(parked.closed)
        assertEquals("LOST", phaseOf("outer-a"))
        assertTrue(logLines.any { "closed after 31 min" in it })
    }

    @Test
    fun `a lost record retried with the same request continues upstream with its evidence`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = BoundedRuntime(capacity = 4)
        val manager = bridge(runtime, clock = clock)
        val sink = RecordingSink()
        manager.interceptor(turn(sessionId = "session-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, sink) { outerOutcome("outer-a") }
        val readId = sink.tools.single().id
        clock.now += 31.minutes.inWholeMilliseconds
        manager.interceptor(turn(sessionId = "session-b"), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertEquals("LOST", phaseOf("outer-a"))

        var posted = ""
        val outcome = manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(requestWithResult(readId, "A"), RecordingSink()) { body ->
                posted = body
                completedOutcome()
            }

        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals("COMPLETED", phaseOf("outer-a"))
        val items = Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray.map { it.jsonObject }
        // The client's callback is folded away; the evidence rides the outer call's own output.
        assertFalse(items.any { it["call_id"]?.jsonPrimitive?.content == readId })
        val output = items.single { it["type"]?.jsonPrimitive?.content == "custom_tool_call_output" }
            .getValue("output").jsonPrimitive.content
        assertTrue("\"status\":\"interrupted\"" in output, output)
        assertTrue(readId in output)
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `interruption evidence past the worker text limit completes with every result`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = BoundedRuntime(capacity = 4, callsPerCell = 2)
        val manager = bridge(runtime, clock = clock)
        val (posted, ids) = lostThenResolved(manager, clock, BIG_RESULT_CHARS)

        val output = interruptionOutput(posted)
        assertTrue(output.length > WORKER_TEXT_BYTES, "evidence is ${output.length} chars")
        assertTrue(ids.all { it in output })
        assertFalse("[truncated" in output)
        assertEquals("COMPLETED", phaseOf("outer-a"))
    }

    @Test
    fun `interruption evidence past the upstream budget is truncated per result`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = BoundedRuntime(capacity = 4, callsPerCell = 2)
        val manager = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtime,
                tempDir.resolve("bridge.json"),
                clock = clock,
                maxOutputChars = SMALL_BUDGET_CHARS,
                log = LogSink { logLines += it },
            ),
        )
        // Each result fits the budget on its own; together they do not.
        val (posted, ids) = lostThenResolved(manager, clock, SMALL_BUDGET_CHARS * 2 / 3)

        val output = interruptionOutput(posted)
        assertTrue(output.length <= SMALL_BUDGET_CHARS, "evidence is ${output.length} chars")
        assertTrue(ids.all { it in output })
        assertEquals(2, Regex("\\[truncated \\d+ chars]").findAll(output).count())
        assertEquals("COMPLETED", phaseOf("outer-a"))
    }

    /** Starts a two-call script, lets the idle reap lose it, then returns the results: the body
     *  posted upstream and the two callback ids. */
    private suspend fun lostThenResolved(
        manager: CodexCodeModeBridge,
        clock: MutableClock,
        resultChars: Int,
    ): Pair<String, List<String>> {
        val sink = RecordingSink()
        manager.interceptor(turn(sessionId = "session-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, sink) { outerOutcome("outer-a") }
        val ids = sink.tools.map { it.id }
        clock.now += 31.minutes.inWholeMilliseconds
        manager.interceptor(turn(sessionId = "session-b"), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertEquals("LOST", phaseOf("outer-a"))

        val results = ids.mapIndexed { index, id -> CodeModeResult(id, "xy"[index].toString().repeat(resultChars)) }
        var posted = ""
        val outcome = manager.interceptor(turn(results = results), null, disableParallel = false)
            .intercept(requestWithResults(results), RecordingSink()) { body ->
                posted = body
                completedOutcome()
            }
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        return posted to ids
    }

    private fun requestWithResults(results: List<CodeModeResult>): String = results.joinToString(
        prefix = """{"input":[{"role":"developer","content":"s"},""",
        postfix = "]}",
    ) { result ->
        """{"type":"function_call","call_id":"${result.id}","name":"Read","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"${result.id}","output":"${result.output}"}"""
    }

    private fun interruptionOutput(posted: String): String =
        Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray.map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "custom_tool_call_output" }
            .getValue("output").jsonPrimitive.content
            .also { assertTrue("\"status\":\"interrupted\"" in it, it) }

    private fun phaseOf(outerCallId: String): String =
        Json.parseToJsonElement(Files.readString(tempDir.resolve("bridge.json"))).jsonObject
            .getValue("records").jsonArray.map { it.jsonObject }
            .single { it.getValue("outerCallId").jsonPrimitive.content == outerCallId }
            .getValue("phase").jsonPrimitive.content

    /** Each started cell emits [callsPerCell] Read calls and then parks until closed; at most [capacity] are open. */
    private class BoundedRuntime(private val capacity: Int, private val callsPerCell: Int = 1) : CodeModeRuntime {
        val cells = mutableListOf<ParkedCell>()
        var starts = 0
        val open: Int get() = cells.count { !it.closed }

        override suspend fun start(source: String, tools: Set<String>): CodeModeCell {
            if (open >= capacity) throw CodeModeCapacityException()
            starts++
            return ParkedCell(callsPerCell).also(cells::add)
        }

        override fun close() = Unit
    }

    private class ParkedCell(private val calls: Int) : CodeModeCell {
        var closed = false
        private var advances = 0

        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
            if (advances++ == 0) {
                CodeModeStep.Calls(List(calls) { CodeModeCall("read-$it", "Read", buildJsonObject {}) })
            } else {
                CodeModeStep.Completed("done")
            }

        override fun close() {
            closed = true
        }
    }
}

private const val BIG_RESULT_CHARS = 40_000
private const val WORKER_TEXT_BYTES = 65_536
private const val SMALL_BUDGET_CHARS = 8_192
