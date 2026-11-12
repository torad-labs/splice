// replay_reasoning=false at the code-mode post: the stored continuity of earlier scripts does not ride back,
// the current turn's does, and replay on posts the body as it is.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.upstream.RoundBody

internal class CodeModePriorReasoningTest {
    private val round = """{"input":[
        {"role":"user","type":"message","content":"first prompt"},
        {"type":"reasoning","id":"rs_old"},
        {"type":"custom_tool_call","call_id":"c1","name":"splice_exec","input":"x"},
        {"type":"custom_tool_call_output","call_id":"c1","output":"ok"},
        {"role":"assistant","type":"message","content":"done"},
        {"role":"user","type":"message","content":"second prompt"},
        {"type":"reasoning","id":"rs_now"},
        {"type":"custom_tool_call","call_id":"c2","name":"splice_exec","input":"x"},
        {"type":"custom_tool_call_output","call_id":"c2","output":"ok"}
    ]}"""

    private fun reasoningPosted(replay: Boolean): List<String> {
        val wire = CodexCodeModeWire(Json, {}, replayReasoning = CodeModeReplayReasoning { replay })
        val posted = wire.upstream(wire.body(RoundBody.Text(round)))
        val text = (posted as? RoundBody.Text)?.text ?: round
        return Json.parseToJsonElement(text).jsonObject["input"]!!.jsonArray
            .map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "reasoning" }
            .map { it["id"]!!.jsonPrimitive.content }
    }

    @Test
    fun `with replay off only this turn's reasoning is posted`() {
        assertEquals(listOf("rs_now"), reasoningPosted(replay = false))
    }

    @Test
    fun `with replay on both turns' reasoning is posted`() {
        assertEquals(listOf("rs_old", "rs_now"), reasoningPosted(replay = true))
    }
}
