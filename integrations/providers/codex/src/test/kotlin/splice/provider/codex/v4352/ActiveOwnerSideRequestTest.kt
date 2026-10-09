// V4-352: an active script is parked across client tool execution. A different request with the same
// conversation key can arrive before those results; it must not own or abandon that script. The
// completed script before it is deliberately on the same history, so the two cases discriminate
// an earlier canonical rewrite from active-owner assignment.
package splice.provider.codex.v4352

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.CodeModeDivergenceMarker
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeBridgeTestSupport
import splice.provider.codex.CodeModeRetention
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.io.IOException

private const val DEVELOPER = """{"role":"developer","content":"s"}"""

class ActiveOwnerSideRequestTest : CodeModeBridgeTestSupport() {

    @Test
    fun `a real callback resumes after an earlier completed rewrite`() {
        runTest {
            val parked = parkSecond()
            resumeSecond(parked)
            assertEquals(2, parked.runtime.cells[1].advances, "the real callback advances the second cell")
            assertTrue(logLines.none { "abandoned record" in it }, logLines.joinToString("\n"))
        }
    }

    @Test
    fun `a same-key side request without callbacks leaves the later script parked`() {
        runTest {
            val parked = parkSecond()
            val side = parked.prefix.dropLast(1) + user("another branch")
            var canonicalSide = ""
            parked.manager.interceptor(turn(), disableParallel = false)
                .intercept(body(side), RecordingSink()) { posted ->
                    canonicalSide = posted
                    completedOutcome()
                }
            val expected = logical(parked.baseline)
            val actual = logical(canonicalSide)
            val firstDifferent = expected.indices.firstOrNull { expected[it] != actual.getOrNull(it) }
            assertEquals(3, firstDifferent, "the earlier completed rewrite leaves this prefix in place")
            assertEquals("user", (expected[3] as JsonObject)["role"]?.jsonPrimitive?.content)
            assertEquals(1, parked.runtime.cells[1].advances, "the side request is not a client result")
            assertTrue(logLines.none { "abandoned record" in it }, logLines.joinToString("\n"))

            resumeSecond(parked)
            assertEquals(2, parked.runtime.cells[1].advances, "the real callback still advances the parked cell")
            assertTrue(logLines.none { "abandoned record" in it }, logLines.joinToString("\n"))
        }
    }

    @Test
    fun `two parked branches resume by their own callback ids`() {
        runTest {
            val parked = parkSecond()
            val side = parked.prefix.dropLast(1) + user("another branch")
            val thirdSink = RecordingSink()
            parked.manager.interceptor(turn(), disableParallel = false)
                .intercept(body(side), thirdSink) { outerOutcome("outer-third") }
            val thirdId = thirdSink.tools.single().id
            assertEquals(1, parked.runtime.cells[1].advances, "the second script stays parked")
            assertEquals(1, parked.runtime.cells[2].advances, "the side branch has its own script")

            resumeSecond(parked)
            assertEquals(2, parked.runtime.cells[1].advances, "the original callback chooses the second script")
            parked.manager.interceptor(turn(thirdId, "C"), disableParallel = false)
                .intercept(body(side + read(thirdId) + output(thirdId, "C")), RecordingSink()) {
                    completedOutcome()
                }
            assertEquals(2, parked.runtime.cells[2].advances, "the side branch callback chooses its own script")
            assertTrue(logLines.none { "abandoned record" in it }, logLines.joinToString("\n"))
        }
    }

    @Test
    fun `replaying identical input after consuming step one serves the same callback without advancing`() = runTest {
        val runtime = twoStepRuntime()
        val manager = bridge(runtime)
        val firstSink = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(body(listOf(DEVELOPER, user("start"))), firstSink) { outerOutcome() }
        val firstId = firstSink.tools.single().id
        val secondSink = RecordingSink()
        manager.interceptor(turn(firstId, "X"), disableParallel = false)
            .intercept(body(listOf(DEVELOPER, user("start"), read(firstId), output(firstId, "X"))), secondSink) {
                error("ordinary result must advance the existing cell")
            }
        val stepTwoId = secondSink.tools.single().id
        val repeated = RecordingSink()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(body(listOf(DEVELOPER, user("start"))), repeated) {
                error("identical request must replay step one without an upstream post")
            }
        assertEquals(
            firstSink.tools.map { Triple(it.id, it.name, it.args.toString()) },
            repeated.tools.map { Triple(it.id, it.name, it.args.toString()) },
            "same callback id and payload for identical input",
        )
        assertEquals(2, runtime.cells.single().advances, "the retry did not advance the cursor")
        val completeInput = body(
            listOf(
                DEVELOPER,
                user("start"),
                read(firstId),
                output(firstId, "X"),
                read(stepTwoId),
                output(stepTwoId, "Y"),
            ),
        )
        val completed = manager.interceptor(turn(stepTwoId, "Y"), disableParallel = false)
            .intercept(completeInput, RecordingSink()) { completedOutcome() }
        assertTrue(completed is TurnOutcome.Success)
        assertEquals(3, runtime.cells.single().advances, "ordinary second result still completes")
    }

    @Test
    fun `identical retry after a restart replays the persisted callback`() = runTest {
        val runtime = twoStepRuntime()
        val input = body(listOf(DEVELOPER, user("start")))
        val original = RecordingSink()
        bridge(runtime).interceptor(turn(), outer(), disableParallel = false)
            .intercept(input, original) { outerOutcome() }
        val snapshot = stateFiles.records().single()
        val issued = snapshot["issued"]!!.jsonArray
        assertEquals(1, issued.size, "callback id must be durable before serving")
        val restarted = bridge(QueuedRuntime(ArrayDeque()))
        val repeated = RecordingSink()
        restarted.interceptor(turn(), disableParallel = false)
            .intercept(input, repeated) { error("persisted retry must not post upstream") }
        assertEquals(
            original.tools.map { Triple(it.id, it.name, it.args.toString()) },
            repeated.tools.map { Triple(it.id, it.name, it.args.toString()) },
        )
        assertEquals(1, runtime.cells.single().advances, "the old process's cell did not advance")
    }

    @Test
    fun `retention one keeps exact replay before trimming and after restart`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("read", "Read"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val retention = CodeModeRetention(perConversation = 1, records = 8)
        val manager = bridge(runtime, retention = retention)
        val input = body(listOf(DEVELOPER, user("start")))
        val first = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(input, first) { outerOutcome() }
        val id = first.tools.single().id
        manager.interceptor(turn(id, "X"), disableParallel = false)
            .intercept(body(listOf(DEVELOPER, user("start"), read(id), output(id, "X"))), RecordingSink()) {
                completedOutcome()
            }
        val immediate = RecordingSink()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(input, immediate) { error("an identical retry must not post upstream") }
        assertEquals(id, immediate.tools.single().id)
        val restarted = bridge(ScriptedRuntime(ArrayDeque()), retention = retention)
        val repeated = RecordingSink()
        restarted.interceptor(turn(), disableParallel = false)
            .intercept(input, repeated) { error("a restarted retry must not post upstream") }
        assertEquals(id, repeated.tools.single().id)
        assertEquals(first.tools.single().args.toString(), repeated.tools.single().args.toString())
        assertEquals(2, runtime.cell.advances, "neither replay reruns the completed worker")
    }

    @Test
    fun `fork with a different accepted result goes upstream and leaves the original script intact`() = runTest {
        val runtime = twoStepRuntime()
        val manager = bridge(runtime)
        val initial = body(listOf(DEVELOPER, user("start")))
        val firstSink = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(initial, firstSink) { outerOutcome() }
        val firstId = firstSink.tools.single().id
        val x = body(listOf(DEVELOPER, user("start"), read(firstId), output(firstId, "X")))
        val secondSink = RecordingSink()
        manager.interceptor(turn(firstId, "X"), disableParallel = false)
            .intercept(x, secondSink) { error("the accepted result is local") }
        var upstreamSends = 0
        val y = body(listOf(DEVELOPER, user("start"), read(firstId), output(firstId, "Y")))
        val side = manager.interceptor(turn(firstId, "Y"), disableParallel = false)
            .intercept(y, RecordingSink()) {
                upstreamSends++
                completedOutcome()
            }
        assertTrue(side is TurnOutcome.Success)
        assertTrue((side as TurnOutcome.Success).usage.codeModeDiverged, "the upstream fallback is marked")
        assertEquals(1, upstreamSends, "the divergent fork sends its own history upstream")
        assertEquals(2, runtime.cells.single().advances, "the fork did not advance A's cell")
        val secondId = secondSink.tools.single().id
        val originalInput = body(
            listOf(
                DEVELOPER,
                user("start"),
                read(firstId),
                output(firstId, "X"),
                read(secondId),
                output(secondId, "Z"),
            ),
        )
        manager.interceptor(turn(secondId, "Z"), disableParallel = false)
            .intercept(originalInput, RecordingSink()) { completedOutcome() }
        assertEquals(3, runtime.cells.single().advances, "A's ordinary chain stays live")
    }

    @Test
    fun `a divergent fork can start its own upstream exec without advancing A`() = runTest {
        val firstSteps = ArrayDeque<CodeModeStep>(
            listOf(
                CodeModeStep.Calls(listOf(call("A-1", "Read"))),
                CodeModeStep.Calls(listOf(call("A-2", "Edit"))),
            ),
        )
        val secondSteps = ArrayDeque<CodeModeStep>(
            listOf(
                CodeModeStep.Calls(listOf(call("B-1", "Read"))),
                CodeModeStep.Calls(listOf(call("B-2", "Edit"))),
            ),
        )
        val runtime = QueuedRuntime(ArrayDeque(listOf(firstSteps, secondSteps)))
        val manager = bridge(runtime)
        val first = RecordingSink()
        val initial = body(listOf(DEVELOPER, user("start")))
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(initial, first) { outerOutcome() }
        val id = first.tools.single().id
        val a = body(listOf(DEVELOPER, user("start"), read(id), output(id, "X")))
        val second = RecordingSink()
        manager.interceptor(turn(id, "X"), disableParallel = false)
            .intercept(a, second) { error("A's result is local") }
        val b = body(listOf(DEVELOPER, user("start"), read(id), output(id, "Y")))
        val bSink = RecordingSink()
        val outcome = manager.interceptor(turn(id, "Y"), disableParallel = false)
            .intercept(b, bSink) { outerOutcome("B-outer") }
        assertTrue(outcome is TurnOutcome.Success)
        assertTrue((outcome as TurnOutcome.Success).usage.codeModeDiverged)
        assertEquals(2, runtime.starts, "B's new exec must start its own worker")
        assertEquals(1, bSink.tools.size, "B's new callback reaches its client")
        val bId = bSink.tools.single().id
        val bHistory = body(
            listOf(DEVELOPER, user("start"), read(id), output(id, "Y"), read(bId), output(bId, "r")),
        )
        manager.interceptor(
            turn(results = listOf(CodeModeResult(id, "Y"), CodeModeResult(bId, "r"))),
            disableParallel = false,
        ).intercept(bHistory, RecordingSink()) { error("B's own callback must resume locally") }
        assertEquals(2, runtime.cells[1].advances, "B's script consumed its own tool result")
        assertEquals(2, runtime.cells[0].advances, "A's parked worker did not advance")
    }

    @Test
    fun `divergent upstream tear keeps its branch marker on the thrown error`() = runTest {
        val runtime = twoStepRuntime()
        val manager = bridge(runtime)
        val first = RecordingSink()
        val input = body(listOf(DEVELOPER, user("start")))
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(input, first) { outerOutcome() }
        val firstId = first.tools.single().id
        val accepted = body(listOf(DEVELOPER, user("start"), read(firstId), output(firstId, "X")))
        manager.interceptor(turn(firstId, "X"), disableParallel = false)
            .intercept(accepted, RecordingSink()) { error("accepted result must stay local") }
        val changed = body(listOf(DEVELOPER, user("start"), read(firstId), output(firstId, "Y")))
        val tear = IOException("upstream closed")
        val caught = try {
            manager.interceptor(turn(firstId, "Y"), disableParallel = false)
                .intercept(changed, RecordingSink()) { throw tear }
            null
        } catch (error: IOException) {
            error
        }
        assertTrue(caught === tear, "the original upstream transport error still propagates")
        assertTrue(
            tear.suppressed.any { it is CodeModeDivergenceMarker },
            "connection-reset telemetry needs provenance",
        )
        assertEquals(2, runtime.cells.single().advances, "B cannot advance A's parked worker")
    }

    @Test
    fun `a visible callback without its result targets its own parked branch`() {
        runTest {
            val parked = parkSecond()
            val side = parked.prefix.dropLast(1) + user("another branch")
            val thirdSink = RecordingSink()
            parked.manager.interceptor(turn(), disableParallel = false)
                .intercept(body(side), thirdSink) { outerOutcome("outer-third") }
            assertEquals(1, parked.runtime.cells[2].advances, "the side script is parked too")

            val incomplete = parked.manager.interceptor(turn(), disableParallel = false)
                .intercept(body(parked.prefix + read(parked.secondId)), RecordingSink()) {
                    error("upstream must not run for an owned call with a missing result")
                }
            assertTrue(incomplete is TurnOutcome.Failure)
            assertEquals(1, parked.runtime.cells[1].advances, "missing result cannot advance the cell")
            assertFalse(parked.runtime.cells[1].closed, "missing result cannot close the cell")
            resumeSecond(parked)
            assertEquals(2, parked.runtime.cells[1].advances, "corrected result chooses the original branch")
            assertTrue(logLines.none { "abandoned record" in it }, logLines.joinToString("\n"))
        }
    }

    private fun twoStepRuntime(): QueuedRuntime {
        val steps = ArrayDeque<CodeModeStep>(
            listOf(
                CodeModeStep.Calls(listOf(call("runtime-1", "Read"))),
                CodeModeStep.Calls(listOf(call("runtime-2", "Edit"))),
                CodeModeStep.Completed("done"),
            ),
        )
        return QueuedRuntime(ArrayDeque(listOf(steps)))
    }

    private data class Parked(
        val manager: CodexCodeModeBridge,
        val runtime: QueuedRuntime,
        val prefix: List<String>,
        val baseline: String,
        val secondId: String,
    )

    private suspend fun parkSecond(): Parked {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(
                        listOf(
                            CodeModeStep.Calls(listOf(call("first-read", "Read"))),
                            CodeModeStep.Completed("first"),
                        ),
                    ),
                    ArrayDeque(
                        listOf(
                            CodeModeStep.Calls(listOf(call("second-read", "Read"))),
                            CodeModeStep.Completed("second"),
                        ),
                    ),
                    ArrayDeque(
                        listOf(
                            CodeModeStep.Calls(listOf(call("third-read", "Read"))),
                            CodeModeStep.Completed("third"),
                        ),
                    ),
                ),
            ),
        )
        val manager = bridge(runtime)
        val firstSink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(body(listOf(DEVELOPER, user("start"))), firstSink) {
                outerOutcome("outer-first").copy(reasoningEnvelopes = listOf(reasoningEnvelope("first-reasoning")))
            }
        val firstId = firstSink.tools.single().id
        val earlierHistory = listOf(DEVELOPER, user("start"), read(firstId), output(firstId, "A"))
        manager.interceptor(turn(firstId, "A"), disableParallel = false)
            .intercept(body(earlierHistory), RecordingSink()) { completedOutcome() }
        assertEquals(2, runtime.cells[0].advances, "the first record is completed before the second starts")

        val prefix = earlierHistory + user("start second")
        val secondSink = RecordingSink()
        var baseline = ""
        manager.interceptor(turn(), disableParallel = false)
            .intercept(body(prefix), secondSink) { posted ->
                baseline = posted
                outerOutcome("outer-second")
            }
        assertEquals(1, runtime.cells[1].advances, "the second record is parked with a client call")
        return Parked(manager, runtime, prefix, baseline, secondSink.tools.single().id)
    }

    private suspend fun resumeSecond(parked: Parked) {
        parked.manager.interceptor(turn(parked.secondId, "B"), disableParallel = false)
            .intercept(body(parked.prefix + read(parked.secondId) + output(parked.secondId, "B")), RecordingSink()) {
                completedOutcome()
            }
    }

    private fun logical(body: String): List<JsonElement> {
        val codec = CodexCodeModeHistoryCodec(Json)
        val input = checkNotNull(codec.root(body)).second
        return codec.conversation(codec.projection.project(input)).body.logicalItems
    }

    private fun body(items: List<String>): String = """{"input":[${items.joinToString(",")}]}"""
    private fun user(text: String): String = """{"role":"user","content":"$text"}"""
    private fun read(id: String): String = """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""
    private fun output(id: String, text: String): String =
        """{"type":"function_call_output","call_id":"$id","output":"$text"}"""

    private fun reasoningEnvelope(id: String): String = checkNotNull(
        ReasoningReplay.encodeReasoningEnvelope(
            buildJsonObject {
                put("type", "reasoning")
                put("id", id)
                put("encrypted_content", "e-$id")
            },
        ),
    )
}

class NativeAncestorPlacementTest : CodeModeBridgeTestSupport() {
    @Test
    fun `restoring an old callback repositions native history without abandoning a later script`() = runTest {
        val runtime = ancestorRuntime()
        val manager = bridge(runtime, retention = CodeModeRetention(records = 32))
        val history = mutableListOf(DEVELOPER, user("start"))
        val earlier = (0..9).map { n -> completeAncestor(manager, history, n) }
        val missing = history.filterNot { earlier.first() in it }
        val current = RecordingSink()
        var before = ""
        manager.interceptor(turn(), disableParallel = false).intercept(body(missing), current) {
            before = it
            outerOutcome("outer-current")
        }
        val activeId = current.tools.single().id
        var after = ""
        val outcome = manager.interceptor(turn(activeId, "last"), disableParallel = false)
            .intercept(body(history + read(activeId) + output(activeId, "last")), RecordingSink()) {
                after = it
                completedOutcome()
            }
        assertTrue(logLines.none { "abandoned record" in it }, logLines.joinToString("\n"))
        assertEquals(2, runtime.cells.last().advances, "the original later script consumes its own callback: $outcome")
        assertEquals(3, oldExecIndex(after) - oldExecIndex(before), "the earlier canonical triple moves an old exec")
    }

    private fun ancestorRuntime(): QueuedRuntime = QueuedRuntime(
        ArrayDeque(
            (0..10).map { n ->
                ArrayDeque<CodeModeStep>(
                    listOf(CodeModeStep.Calls(listOf(call("ancestor-$n", "Read"))), CodeModeStep.Completed("done-$n")),
                )
            },
        ),
    )

    private suspend fun completeAncestor(
        manager: CodexCodeModeBridge,
        history: MutableList<String>,
        n: Int,
    ): String {
        history += user("start-$n")
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(body(history), sink) {
            outerOutcome("outer-$n").copy(
                emittedText = true,
                bodyText = "prose-$n",
                reasoningEnvelopes = listOf(reasoningEnvelope("ancestor-reason-$n")),
            )
        }
        val id = sink.tools.single().id
        history += read(id)
        history += output(id, "done-$n")
        manager.interceptor(turn(id, "done-$n"), disableParallel = false)
            .intercept(body(history), RecordingSink()) { completedOutcome() }
        return id
    }

    private fun oldExecIndex(bodyJson: String): Int =
        Json.parseToJsonElement(bodyJson).jsonObject.getValue("input").jsonArray.indexOfFirst {
            (it as? JsonObject)?.get("call_id")?.jsonPrimitive?.content == "outer-1"
        }

    private fun body(items: List<String>): String = """{"input":[${items.joinToString(",")}]}"""
    private fun user(text: String): String = """{"role":"user","content":"$text"}"""
    private fun read(id: String): String = """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""
    private fun output(id: String, text: String): String =
        """{"type":"function_call_output","call_id":"$id","output":"$text"}"""

    private fun reasoningEnvelope(id: String): String = checkNotNull(
        ReasoningReplay.encodeReasoningEnvelope(
            buildJsonObject {
                put("type", "reasoning")
                put("id", id)
                put("encrypted_content", "e-$id")
            },
        ),
    )
}
