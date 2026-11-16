// Goal (muse-tool-cache, decision 4): when the model calls a tool that arrived mid-conversation through the head's
// caller, Claude Code is handed a call of that tool, under its own name, with its arguments, and never sees the caller.
// Anyone can tell by reading the blocks Claude Code receives for a backend stream.
package splice.dialect.responses.stream

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome

private const val CALLER = "CallToolByName"
private const val LONG_NAME = "mcp__claude_ai_Team_Workspace_Calendar__list_the_events_of_one_person_for_a_week"

class ResponsesCallerCallsTest {

    @Test
    fun `a call through the caller reaches Claude Code as the named tool's, with its arguments`() = runTest {
        val args = """{"tool":"SendMessage","arguments":{"to":"lead","message":"green"}}"""
        val sink = drive(callEvents(0, "call_1", CALLER, deltas = listOf(args.take(20), args.drop(20))) + completed)

        assertEquals(listOf("openTool#0(call_1,SendMessage)"), sink.calls.filter { it.startsWith("openTool") })
        assertEquals("""{"to":"lead","message":"green"}""", json(sink, 0))
        assertTrue(sink.calls.none { CALLER in it }, "${sink.calls}")
    }

    @Test
    fun `a call whose arguments come only with its end is handed over whole`() = runTest {
        val args = """{"tool":"$LONG_NAME","arguments":{"person":"ana"}}"""
        val sink = drive(callEvents(0, "call_1", CALLER, deltas = emptyList(), done = args) + completed)

        assertEquals(
            listOf("openTool#0(call_1,$LONG_NAME)"),
            sink.calls.filter { it.startsWith("openTool") },
            "a name past 64 characters reaches Claude Code whole",
        )
        assertEquals("""{"person":"ana"}""", json(sink, 0))
    }

    @Test
    fun `arguments the model wrote as JSON text are the object they spell`() = runTest {
        val args = """{"tool":"TaskStop","arguments":"{\"task_id\":\"7\"}"}"""
        val sink = drive(callEvents(0, "call_1", CALLER, deltas = listOf(args)) + completed)

        assertEquals(listOf("openTool#0(call_1,TaskStop)"), sink.calls.filter { it.startsWith("openTool") })
        assertEquals("""{"task_id":"7"}""", json(sink, 0))
    }

    @Test
    fun `a listed tool's call and a caller call in one answer each reach Claude Code as meant`() = runTest {
        val caller = """{"tool":"Monitor","arguments":{"command":"tail"}}"""
        val sink = drive(
            callEvents(0, "call_1", "Bash", deltas = listOf("""{"command":"ls"}""")) +
                callEvents(1, "call_2", CALLER, deltas = listOf(caller)) + completed,
        )

        assertEquals(
            listOf("openTool#0(call_1,Bash)", "openTool#1(call_2,Monitor)"),
            sink.calls.filter { it.startsWith("openTool") },
        )
        assertEquals("""{"command":"ls"}""", json(sink, 0))
        assertEquals("""{"command":"tail"}""", json(sink, 1))
    }

    @Test
    fun `a caller call that names no tool goes on as the model made it, for Claude Code to refuse`() = runTest {
        val args = """{"arguments":{"x":"1"}}"""
        val sink = drive(callEvents(0, "call_1", CALLER, deltas = listOf(args)) + completed)

        assertEquals(listOf("openTool#0(call_1,$CALLER)"), sink.calls.filter { it.startsWith("openTool") })
        assertEquals(args, json(sink, 0))
    }

    private suspend fun drive(events: List<JsonObject>): RecordingSink {
        val sink = RecordingSink()
        val outcome = ResponsesCallerTranslator(ResponsesStreamTranslator(ctx())).driveTurn(events.asFlow(), sink)
        assertTrue((outcome as TurnOutcome.Success).hasToolUse, "$outcome")
        return sink
    }
}

/** The arguments Claude Code received for the tool block at [index]. */
private fun json(sink: RecordingSink, index: Int): String =
    sink.calls.filter { it.startsWith("json#$index:") }.joinToString("") { it.removePrefix("json#$index:") }

/** One function call as Meta streams it: added, its argument deltas, the end of its arguments, and its end. */
private fun callEvents(
    at: Int,
    id: String,
    name: String,
    deltas: List<String>,
    done: String = deltas.joinToString(""),
): List<JsonObject> {
    val item = """{"type":"function_call","id":"fc_$at","call_id":"$id","name":"$name""""
    val whole = JsonPrimitive(done).toString()
    return listOf(
        ev("""{"type":"response.output_item.added","output_index":$at,"item":$item,"arguments":""}}"""),
    ) + deltas.map { delta ->
        ev("""{"type":"response.function_call_arguments.delta","output_index":$at,"delta":${JsonPrimitive(delta)}}""")
    } + listOf(
        ev("""{"type":"response.function_call_arguments.done","output_index":$at,"arguments":$whole}"""),
        ev("""{"type":"response.output_item.done","output_index":$at,"item":$item,"arguments":$whole}}"""),
    )
}

private val completed = listOf(
    ev("""{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":100,"output_tokens":7}}}"""),
)

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
