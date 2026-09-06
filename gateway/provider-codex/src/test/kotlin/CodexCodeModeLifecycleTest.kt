import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.spi.CodeModeResult
import splice.spi.CodeModeStep
import kotlin.time.Duration.Companion.hours

class CodexCodeModeLifecycleTest : CodeModeBridgeTestSupport() {
    @Test
    fun `completed mapping replays opaque pair after final upstream failure without rerunning source`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("answer"))))
        val first = bridge(runtime)
        var posts = 0
        val failed = first.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) {
                posts++
                if (posts == 1) outerOutcome() else TurnOutcome.Failure(ErrorType.API_ERROR, "down")
            }
        assertTrue(failed is TurnOutcome.Failure)

        val replacement = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run"))))
        var replayed = ""
        val retried = bridge(replacement).interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { body ->
                replayed = body
                completedOutcome()
            }

        assertTrue(retried is TurnOutcome.Success)
        assertEquals(0, replacement.starts)
        assertTrue("custom_tool_call" in replayed)
        assertTrue("custom_tool_call_output" in replayed)
        assertTrue("answer" in replayed)
    }

    @Test
    fun `retry replays every immediate cell from a failed continuation in order`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(listOf(CodeModeStep.Completed("one"))),
                    ArrayDeque(listOf(CodeModeStep.Completed("two"))),
                ),
            ),
        )
        var posts = 0
        val failed = bridge(runtime).interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) {
                posts++
                when (posts) {
                    1 -> outerOutcome("outer-1")
                    2 -> outerOutcome("outer-2")
                    else -> TurnOutcome.Failure(ErrorType.API_ERROR, "down")
                }
            }
        assertTrue(failed is TurnOutcome.Failure)

        val replacement = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run"))))
        var replayed = ""
        val retried = bridge(replacement).interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { body ->
                replayed = body
                completedOutcome()
            }

        assertTrue(retried is TurnOutcome.Success)
        assertEquals(0, replacement.starts)
        assertTrue("outer-1" in replayed)
        assertTrue("outer-2" in replayed)
        assertTrue(replayed.indexOf("outer-1") < replayed.indexOf("outer-2"))
        assertTrue("one" in replayed)
        assertTrue("two" in replayed)
    }

    @Test
    fun `full registry refuses another admission without evicting an active cell`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("r1", "Read"))))),
                    ArrayDeque(listOf(CodeModeStep.Completed("second"))),
                ),
            ),
        )
        val manager = bridge(runtime, maxRecords = 1)
        val firstSink = RecordingSink()
        manager.interceptor(turn(sessionId = "session-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, firstSink) { outerOutcome("outer-a") }

        val second = manager.interceptor(turn(sessionId = "session-b"), outer("outer-b"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-b") }

        assertTrue(second is TurnOutcome.Failure)
        assertEquals(1, runtime.starts)
        assertFalse(runtime.cells.single().closed)
    }

    @Test
    fun `consecutive immediate scripts are bounded across the intercept drive`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(listOf(CodeModeStep.Completed("one"))),
                    ArrayDeque(listOf(CodeModeStep.Completed("two"))),
                    ArrayDeque(listOf(CodeModeStep.Completed("three"))),
                ),
            ),
        )
        val manager = bridge(runtime, maxRounds = 2)
        var posts = 0
        val outcome = manager.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) {
                posts++
                TurnOutcome.Success(
                    false,
                    false,
                    Usage(),
                    customCalls = listOf(outer("outer-$posts", "source-$posts")),
                )
            }

        assertTrue(outcome is TurnOutcome.Failure)
        assertEquals(2, runtime.starts)
        assertTrue((outcome as TurnOutcome.Failure).message.contains("round limit"))
    }

    @Test
    fun `expired mapping reports history failure after registry reconstruction`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("r1", "Read"))))))
        val manager = bridge(runtime, ttl = 1.hours, clock = clock)
        val sink = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, sink) { outerOutcome() }
        val resultId = sink.tools.single().id
        clock.now += 2.hours.inWholeMilliseconds

        val restored = bridge(
            ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run")))),
            ttl = 1.hours,
            clock = clock,
        )
        val outcome = restored.interceptor(turn(resultId, "A"), null, disableParallel = false)
            .intercept(requestWithResult(resultId, "A"), RecordingSink()) { completedOutcome() }

        assertTrue(outcome is TurnOutcome.Failure)
        assertTrue((outcome as TurnOutcome.Failure).message.contains("expired"))
    }

    @Test
    fun `missing result does not consume the cell and corrected retry advances once`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("r1", "Read"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, sink) { outerOutcome() }
        val id = sink.tools.single().id

        val rejected = manager.interceptor(turn(), null, disableParallel = false)
            .intercept(requestWithCall(id), RecordingSink()) { error("upstream must not run") }
        assertTrue(rejected is TurnOutcome.Failure)
        assertEquals(1, runtime.cell.advances)
        assertFalse(runtime.cell.closed)

        val corrected = manager.interceptor(turn(id, "A"), null, disableParallel = false)
            .intercept(requestWithResult(id, "A"), RecordingSink()) { completedOutcome() }
        assertTrue(corrected is TurnOutcome.Success)
        assertEquals(2, runtime.cell.advances)
    }

    @Test
    fun `duplicate result does not consume the cell and corrected retry advances once`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("r1", "Read"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, sink) { outerOutcome() }
        val id = sink.tools.single().id
        val duplicateTurn = turn(results = listOf(CodeModeResult(id, "A"), CodeModeResult(id, "B")))

        val rejected = manager.interceptor(duplicateTurn, null, disableParallel = false)
            .intercept(requestWithResult(id, "A"), RecordingSink()) { error("upstream must not run") }
        assertTrue(rejected is TurnOutcome.Failure)
        assertEquals(1, runtime.cell.advances)
        assertFalse(runtime.cell.closed)

        manager.interceptor(turn(id, "A"), null, disableParallel = false)
            .intercept(requestWithResult(id, "A"), RecordingSink()) { completedOutcome() }
        assertEquals(2, runtime.cell.advances)
    }

    @Test
    fun `conflicting consumed replay fails without advancing and corrected history resumes`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("r1", "Read"))),
                    CodeModeStep.Calls(listOf(call("r2", "Edit"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val manager = bridge(runtime)
        val firstSink = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, firstSink) { outerOutcome() }
        val firstId = firstSink.tools.single().id
        val secondSink = RecordingSink()
        manager.interceptor(turn(firstId, "A"), null, disableParallel = false)
            .intercept(requestWithResult(firstId, "A"), secondSink) { error("upstream must not run") }
        val secondId = secondSink.tools.single().id

        val conflicting = turn(
            results = listOf(CodeModeResult(firstId, "different"), CodeModeResult(secondId, "B")),
        )
        val rejected = manager.interceptor(conflicting, null, disableParallel = false)
            .intercept(requestWithTwoResults(firstId, secondId), RecordingSink()) { error("upstream must not run") }
        assertTrue(rejected is TurnOutcome.Failure)
        assertEquals(2, runtime.cell.advances)
        assertFalse(runtime.cell.closed)

        val corrected = turn(results = listOf(CodeModeResult(firstId, "A"), CodeModeResult(secondId, "B")))
        manager.interceptor(corrected, null, disableParallel = false)
            .intercept(requestWithTwoResults(firstId, secondId), RecordingSink()) { completedOutcome() }
        assertEquals(3, runtime.cell.advances)
    }

    @Test
    fun `head stop during runtime startup prevents late attachment and records lost state`() = runTest {
        val runtime = BlockingRuntime()
        val manager = bridge(runtime)
        val sink = RecordingSink()
        val running = async {
            manager.interceptor(turn(), outer(), disableParallel = false)
                .intercept(BASE_REQUEST, sink) { outerOutcome() }
        }
        runtime.started.await()

        manager.onHeadStop()
        runtime.release.complete(
            ScriptedCell(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("r1", "Read")))))).also {
                runtime.cell = it
            },
        )
        val outcome = running.await()

        assertTrue(outcome is TurnOutcome.Failure)
        assertTrue(checkNotNull(runtime.cell).closed)
        assertTrue(sink.tools.isEmpty())
        val restored = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run")))))
        val retry = restored.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertTrue(retry is TurnOutcome.Failure)
        assertTrue((retry as TurnOutcome.Failure).message.contains("source was not rerun"))
    }

    @Test
    fun `head stop advances generation and a fresh cell starts successfully`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("r1", "Read"))))),
                    ArrayDeque(listOf(CodeModeStep.Completed("fresh"))),
                ),
            ),
        )
        val manager = bridge(runtime)
        manager.interceptor(turn(sessionId = "session-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-a") }
        manager.onHeadStop()

        var posts = 0
        val fresh = manager.interceptor(turn(sessionId = "session-b"), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) {
                posts++
                if (posts == 1) outerOutcome("outer-b") else completedOutcome()
            }

        assertTrue(fresh is TurnOutcome.Success)
        assertEquals(2, runtime.starts)
    }
}
