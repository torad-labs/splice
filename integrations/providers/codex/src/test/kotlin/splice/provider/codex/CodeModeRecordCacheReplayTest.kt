package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep
import java.nio.file.Files

class CodeModeRecordCacheReplayTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a cold callback history sends the same opaque script after eviction and restart`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("accepted"))),
        )
        val retention = CodeModeRetention(bytes = 1)
        val manager = bridge(runtime, retention = retention)
        val first = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, first) { RoundResult.Outcome(outerOutcome()) }
        val id = first.tools.single().id
        val client = requestWithResult(id, "A")
        var original = ""
        manager.interceptor(turn(id, "A"), disableParallel = false)
            .intercept(client, RecordingSink()) { body ->
                original = body
                RoundResult.Outcome(completedOutcome())
            }
        assertTrue(logLines.any { "moved to durable cache" in it }, "$logLines")
        val restarted = bridge(
            ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("source must never run again")))),
            retention = retention,
        )
        for (current in listOf(manager, restarted)) {
            var sent = ""
            val outcome = current.interceptor(turn(id, "A"), disableParallel = false)
                .intercept(client, RecordingSink()) { body ->
                    sent = body
                    RoundResult.Outcome(completedOutcome())
                }.turn()
            assertTrue(outcome is TurnOutcome.Success)
            assertEquals(Json.parseToJsonElement(original), Json.parseToJsonElement(sent))
            assertTrue("custom_tool_call_output" in sent)
            assertFalse(logLines.any { "remain ordinary after history reconstruction" in it }, "$logLines")
        }
        manager.onHeadStop()
        restarted.onHeadStop()
    }

    @Test
    fun `unreadable cold execution state returns a failure without replacing accepted work`() = runTest {
        val manager = bridge(
            ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("accepted")))),
            retention = CodeModeRetention(bytes = 1),
        )
        var opening = true
        manager.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) {
                val result = if (opening) outerOutcome() else completedOutcome()
                opening = false
                RoundResult.Outcome(result)
            }
        val file = stateFiles.files().single()
        val saved = Files.readString(file)
        Files.writeString(file, "{broken\n")
        val failed = manager.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) {
                error("unreadable execution state must not reach upstream")
            }.turn()
        assertTrue(failed is TurnOutcome.Failure)
        Files.writeString(file, saved)
        var sent = ""
        val retried = manager.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { body ->
                sent = body
                RoundResult.Outcome(completedOutcome())
            }.turn()
        assertTrue(retried is TurnOutcome.Success)
        val outputs = Json.parseToJsonElement(sent).jsonObject.getValue("input").jsonArray
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "custom_tool_call_output" }
        assertTrue(outputs.any { "accepted" in it.toString() })
        manager.onHeadStop()
    }
}
