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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeBridgeTestSupport
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.upstream.codemode.CodeModeStep

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
