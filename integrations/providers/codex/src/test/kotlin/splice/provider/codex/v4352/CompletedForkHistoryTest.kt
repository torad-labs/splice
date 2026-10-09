// NEW: branch exclusions survive every hidden post, including completion of a resumed script.
package splice.provider.codex.v4352

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeBridgeTestSupport
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.turn
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep

internal class CompletedForkHistoryTest : CodeModeBridgeTestSupport() {
    @Test
    fun `an immediate fork completion never rewrites the changed result through the original record`() = runTest {
        exerciseCompletedFork(ArrayDeque(listOf(CodeModeStep.Completed("fork done"))))
    }

    @Test
    fun `a resumed fork completion keeps the same excluded original record`() = runTest {
        exerciseCompletedFork(
            ArrayDeque(
                listOf(CodeModeStep.Calls(listOf(call("fork-read", "Read"))), CodeModeStep.Completed("fork done")),
            ),
        )
    }

    private suspend fun exerciseCompletedFork(forkSteps: ArrayDeque<CodeModeStep>) {
        val originalSteps = ArrayDeque<CodeModeStep>(
            listOf(CodeModeStep.Calls(listOf(call("original-read", "Read"))), CodeModeStep.Completed("original done")),
        )
        val runtime = QueuedRuntime(ArrayDeque(listOf(originalSteps, forkSteps)))
        val manager = bridge(runtime)
        val first = RecordingSink()
        val initial = listOf("""{"role":"developer","content":"s"}""", """{"role":"user","content":"start"}""")
        manager.interceptor(turn(), disableParallel = false)
            .intercept(body(initial), first) { RoundResult.Outcome(outerOutcome("original-outer")) }
        val originalId = first.tools.single().id
        val original = initial + read(originalId) + output(originalId, "X")
        manager.interceptor(turn(originalId, "X"), disableParallel = false)
            .intercept(body(original), RecordingSink()) { RoundResult.Outcome(completedOutcome()) }

        val fork = initial + read(originalId) + output(originalId, "Y")
        val forkSink = RecordingSink()
        var posts = 0
        var continued = ""
        val outcome = manager.interceptor(turn(originalId, "Y"), disableParallel = false)
            .intercept(body(fork), forkSink) { posted ->
                posts++
                RoundResult.Outcome(
                    if (posts == 1) {
                        outerOutcome("fork-outer")
                    } else {
                        continued = posted
                        completedOutcome()
                    },
                )
            }.turn()
        assertTrue(outcome is TurnOutcome.Success)
        if (forkSink.tools.isNotEmpty()) {
            val forkId = forkSink.tools.single().id
            manager.interceptor(
                turn(results = listOf(CodeModeResult(originalId, "Y"), CodeModeResult(forkId, "fork result"))),
                disableParallel = false,
            ).intercept(body(fork + read(forkId) + output(forkId, "fork result")), RecordingSink()) { posted ->
                continued = posted
                RoundResult.Outcome(completedOutcome())
            }
        }
        val items = logical(continued)
        val forkOutput = Json.parseToJsonElement(output(originalId, "Y"))
        assertTrue(forkOutput in items, "the fork's changed output stays ordinary")
        assertTrue(items.none { (it as? JsonObject)?.get("call_id")?.jsonPrimitive?.content == "original-outer" })
        assertTrue(items.any { (it as? JsonObject)?.get("call_id")?.jsonPrimitive?.content == "fork-outer" })
        assertEquals(2, runtime.cells[0].advances, "the original record remains untouched")
    }

    private fun logical(body: String): List<JsonElement> {
        val codec = CodexCodeModeHistoryCodec(Json)
        val input = checkNotNull(codec.root(body)).second
        return codec.conversation(codec.projection.project(input)).body.logicalItems
    }

    private fun body(items: List<String>): String = """{"input":[${items.joinToString(",")}]}"""
    private fun read(id: String): String =
        """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""

    private fun output(id: String, text: String): String =
        """{"type":"function_call_output","call_id":"$id","output":"$text"}"""
}
