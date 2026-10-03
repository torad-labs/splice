package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeCapacityException
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
        assertTrue("Call exec again" in output && "directly" !in output, output)
        assertTrue("\"sourceRerun\":false" in output)
        assertTrue(logLines.any { "capacity reached" in it })
    }

    @Test
    fun `capacity reclaims a parked cell only after its session ended`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = BoundedRuntime(capacity = 1)
        val manager = bridge(runtime, clock = clock)
        manager.interceptor(turn(sessionId = "session-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-a") }
        val parked = runtime.cells.single()
        clock.now += 3.minutes.inWholeMilliseconds
        deadSessions += "session-a"

        val sink = RecordingSink()
        val outcome = manager.interceptor(turn(sessionId = "session-b"), outer("outer-b"), disableParallel = false)
            .intercept(BASE_REQUEST, sink) { outerOutcome("outer-b") }

        assertTrue(outcome is TurnOutcome.Success)
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertTrue(parked.closed)
        assertEquals(2, runtime.starts)
        assertEquals(1, runtime.open)
        assertEquals("LOST", phaseOf("outer-a"))
        assertTrue(logLines.any { "session ended" in it && "outer-a" in it })
    }

    @Test
    fun `a dead session is reaped at capacity without changing its retained history`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = BoundedRuntime(capacity = 4)
        val manager = bridge(runtime, clock = clock)
        manager.interceptor(turn(sessionId = "session-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-a") }
        val parked = runtime.cells.single()
        clock.now += 31.minutes.inWholeMilliseconds
        deadSessions += "session-a"

        reapIdleCell(manager)

        assertTrue(parked.closed)
        assertEquals("LOST", phaseOf("outer-a"))
        assertTrue(logLines.any { "session ended" in it })
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
        deadSessions += "session-a"
        reapIdleCell(manager)
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
                { runtime },
                stateLocation(),
                clock = clock,
                maxOutputChars = SMALL_BUDGET_CHARS,
                log = LogSink { logLines += it },
                sessionAlive = CodeModeSessionAlive { id -> if (id in deadSessions) false else null },
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
        deadSessions += "session-a"
        reapIdleCell(manager)
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
        stateFiles.records()
            .single { it.getValue("outerCallId").jsonPrimitive.content == outerCallId }
            .getValue("phase").jsonPrimitive.content
}

/** Each started cell emits [callsPerCell] Read calls and then parks until closed; at most [capacity] are open. */
private class BoundedRuntime(private val capacity: Int, private val callsPerCell: Int = 1) : CodeModeRuntime {
    val cells = mutableListOf<ParkedCell>()
    var starts = 0
    val open: Int get() = cells.count { !it.closed }

    override suspend fun start(
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell {
        if (open >= capacity) {
            throw CodeModeCapacityException("synthetic pool full: every retained engine has a live cell")
        }
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

/** Unknown liveness cannot keep parked worker slots forever, but an advancing cell is never idle. */
class CodeModeUnknownCapacityTest : CodeModeBridgeTestSupport() {
    @Test
    fun `full pool reclaims the globally oldest unknown parked cell at the idle bound`() = runTest {
        val clock = MutableClock(2_000)
        val runtime = BoundedRuntime(capacity = 2)
        val manager = bridge(runtime, clock = clock)
        manager.interceptor(turn(sessionId = "newer"), outer("outer-newer"), false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-newer") }
        clock.now = 1_000
        val olderSink = RecordingSink()
        manager.interceptor(turn(sessionId = "older"), outer("outer-older"), false)
            .intercept(BASE_REQUEST, olderSink) { outerOutcome("outer-older") }
        clock.now = 1_000 + 30.minutes.inWholeMilliseconds
        try {
            val sink = RecordingSink()
            val outcome = incoming(manager, sink)
            assertTrue(outcome is TurnOutcome.Success && outcome.hasToolUse, "pool-full refusal: $outcome; $logLines")
            assertFalse(runtime.cells[0].closed, "record insertion order is not least-recently-used order")
            assertTrue(runtime.cells[1].closed)
            assertEquals(3, runtime.starts)
            val lost = stateFiles.records().single { it["outerCallId"]?.jsonPrimitive?.content == "outer-older" }
            assertEquals("LOST", lost.getValue("phase").jsonPrimitive.content)
            assertEquals(1, lost.getValue("pending").jsonArray.size, "callback evidence survives the eviction")
            assertTrue("source was not rerun" in lost.getValue("error").jsonPrimitive.content)
            assertEquals(1_000L, lost.getValue("updatedAt").jsonPrimitive.content.toLong())
            assertEquals(1, lost.getValue("issued").jsonArray.size, "durable issuance survives the eviction")
            val callback = olderSink.tools.single().id
            val resumedSink = RecordingSink()
            val resumed = manager.interceptor(turn(callback, "A", sessionId = "older"), null, false)
                .intercept(requestWithResult(callback, "A"), resumedSink) { completedOutcome() }
            assertTrue(resumed is TurnOutcome.Success && !resumed.hasToolUse)
            assertTrue(resumedSink.tools.isEmpty())
            assertEquals(3, runtime.starts, "the reclaimed source is never restarted")
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    fun `capacity protects positively alive cells and reports unknown and alive shares`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = BoundedRuntime(capacity = 2)
        val manager = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                { runtime },
                stateLocation(),
                clock = clock,
                cellClock = splice.core.util.ElapsedClock(clock::millis),
                log = LogSink { logLines += it },
                sessionAlive = CodeModeSessionAlive { if (it == "alive") true else null },
            ),
        )
        try {
            manager.interceptor(turn(sessionId = "alive"), outer("outer-alive"), false)
                .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-alive") }
            clock.now += 29.minutes.inWholeMilliseconds
            manager.interceptor(turn(sessionId = "unknown"), outer("outer-unknown"), false)
                .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-unknown") }
            val refusal = incoming(manager)
            assertTrue(refusal is TurnOutcome.Success && !refusal.hasToolUse)
            assertTrue(runtime.cells.none { it.closed })
            assertTrue(
                logLines.any { "dead=0 unknown=1 alive=1" in it && "oldestIdleMs=1740000" in it },
                logLines.toString(),
            )
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    fun `an unknown session executing past the idle bound cannot be evicted`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = BoundedRuntime(capacity = 1)
        val advancing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = object : CodeModeRuntime by runtime {
            override suspend fun startSession(
                sessionKey: String,
                source: String,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell {
                val cell = runtime.start(source, tools, descriptions)
                return object : CodeModeCell by cell {
                    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                        if (results.isNotEmpty()) {
                            advancing.complete(Unit)
                            release.await()
                        }
                        return cell.advance(results)
                    }
                }
            }
        }
        val manager = bridge(gated, clock = clock)
        val sink = RecordingSink()
        manager.interceptor(turn(), outer("outer-running"), false)
            .intercept(BASE_REQUEST, sink) { outerOutcome("outer-running") }
        val id = sink.tools.single().id
        val request = async {
            manager.interceptor(turn(id, "A"), null, false)
                .intercept(requestWithResult(id, "A"), RecordingSink()) { completedOutcome() }
        }
        advancing.await()
        clock.now += 31.minutes.inWholeMilliseconds
        try {
            val refusal = incoming(manager)
            assertTrue(refusal is TurnOutcome.Success && !refusal.hasToolUse)
            assertFalse(runtime.cells.single().closed)
            assertTrue(logLines.any { "executing=1" in it }, logLines.toString())
        } finally {
            release.complete(Unit)
            request.await()
            manager.onHeadStop()
        }
    }

    private suspend fun incoming(manager: CodexCodeModeBridge, sink: RecordingSink = RecordingSink()): TurnOutcome {
        var posts = 0
        return manager.interceptor(turn(sessionId = "incoming"), null, false)
            .intercept(BASE_REQUEST, sink) {
                if (posts++ == 0) outerOutcome("outer-incoming") else completedOutcome()
            }
    }
}

private const val BIG_RESULT_CHARS = 40_000
private const val WORKER_TEXT_BYTES = 65_536
private const val SMALL_BUDGET_CHARS = 8_192
