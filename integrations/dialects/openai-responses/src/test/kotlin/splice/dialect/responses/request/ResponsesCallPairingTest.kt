// Goal (fix 1 of the Oct 27 Responses fixes, artifacts/responses-gaps-2026-10-27/goals.md): a call whose result never
// existed goes out the way Codex sends one, and a result whose call is gone is not sent, as Codex's history
// normalization does. Its invariants are "every call (function/custom) has a corresponding output entry" and "every
// output has a corresponding call entry or names an external tool event" (codex-rs
// core/src/context_manager/history.rs, normalize_history, over normalize.rs's ensure_call_outputs_present and
// remove_orphan_outputs). Codex answers a function or local shell call with a function_call_output "aborted", a custom
// tool call with a custom_tool_call_output "aborted", and a tool search with a completed client tool_search_output
// that lists no tools, each right after its call. The stand-in carries an id only when its call has one: the prefix,
// an underscore and the version 5 UUID of "<prefix>:<call item id>" in Codex's namespace
// 90d38d3e-6a5b-4d52-bfe2-2f1e634bfac4 (synthetic_output_id). The ids below were computed from that formula with
// Python's uuid.uuid5, not by this code.
package splice.dialect.responses.request

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResponsesCallPairingTest {
    private val pairing = ResponsesCallPairing()

    private fun items(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    @Test
    fun `a function call whose result never existed is answered aborted right after it, with Codex's id`() {
        val input = items(
            """[{"role":"user","content":"go"},
                {"type":"function_call","id":"fc_1","call_id":"call_1","name":"Read","arguments":"{}"},
                {"role":"user","content":"next"}]""",
        )
        val expected = items(
            """[{"role":"user","content":"go"},
                {"type":"function_call","id":"fc_1","call_id":"call_1","name":"Read","arguments":"{}"},
                {"type":"function_call_output","id":"fco_d08ee114-ed5d-5706-8dbd-16ccacd40aa4","call_id":"call_1",
                 "output":"aborted"},
                {"role":"user","content":"next"}]""",
        )
        assertEquals(expected, pairing.pair(input))
    }

    @Test
    fun `a custom tool call whose result never existed is answered with an aborted custom output`() {
        val input = items(
            """[{"type":"custom_tool_call","id":"ctc_1","call_id":"call_exec","name":"exec","input":"text('x')"}]""",
        )
        val expected = items(
            """[{"type":"custom_tool_call","id":"ctc_1","call_id":"call_exec","name":"exec","input":"text('x')"},
                {"type":"custom_tool_call_output","id":"ctco_ffd2c872-81a7-5a00-b6f6-884096d1f522",
                 "call_id":"call_exec","output":"aborted"}]""",
        )
        assertEquals(expected, pairing.pair(input))
    }

    @Test
    fun `a tool search whose result never existed is answered with a completed client search that found nothing`() {
        val input = items(
            """[{"type":"tool_search_call","id":"tsc_1","call_id":"call_search","execution":"client",
                 "arguments":{"query":"files"}}]""",
        )
        val expected = items(
            """[{"type":"tool_search_call","id":"tsc_1","call_id":"call_search","execution":"client",
                 "arguments":{"query":"files"}},
                {"type":"tool_search_output","id":"tso_f380c6b3-5815-5031-91ee-dd1c77f963ee","call_id":"call_search",
                 "status":"completed","execution":"client","tools":[]}]""",
        )
        assertEquals(expected, pairing.pair(input))
    }

    @Test
    fun `a local shell call whose result never existed is answered with an aborted function output`() {
        val input = items(
            """[{"type":"local_shell_call","id":"lsc_1","call_id":"call_shell","status":"completed",
                 "action":{"type":"exec","command":["ls"]}}]""",
        )
        val expected = items(
            """[{"type":"local_shell_call","id":"lsc_1","call_id":"call_shell","status":"completed",
                 "action":{"type":"exec","command":["ls"]}},
                {"type":"function_call_output","id":"fco_bec05c95-9f33-5829-a6f6-fb31809905c9","call_id":"call_shell",
                 "output":"aborted"}]""",
        )
        assertEquals(expected, pairing.pair(input))
    }

    @Test
    fun `a call without an item id gets a stand-in without one, as Codex's older history does`() {
        val input = items("""[{"type":"function_call","call_id":"toolu_1","name":"Read","arguments":"{}"}]""")
        val expected = items(
            """[{"type":"function_call","call_id":"toolu_1","name":"Read","arguments":"{}"},
                {"type":"function_call_output","call_id":"toolu_1","output":"aborted"}]""",
        )
        assertEquals(expected, pairing.pair(input))
    }

    @Test
    fun `results whose call is gone are not sent, and a server search result or a shell result stays`() {
        val input = items(
            """[{"role":"user","content":"go"},
                {"type":"function_call_output","call_id":"gone_1","output":"stale"},
                {"type":"custom_tool_call_output","call_id":"gone_2","output":"stale"},
                {"type":"tool_search_output","call_id":"gone_3","status":"completed","execution":"client","tools":[]},
                {"type":"tool_search_output","call_id":"server_1","status":"completed","execution":"server",
                 "tools":[]},
                {"type":"local_shell_call","call_id":"shell_1","status":"completed",
                 "action":{"type":"exec","command":["ls"]}},
                {"type":"function_call_output","call_id":"shell_1","output":"listing"}]""",
        )
        val expected = items(
            """[{"role":"user","content":"go"},
                {"type":"tool_search_output","call_id":"server_1","status":"completed","execution":"server",
                 "tools":[]},
                {"type":"local_shell_call","call_id":"shell_1","status":"completed",
                 "action":{"type":"exec","command":["ls"]}},
                {"type":"function_call_output","call_id":"shell_1","output":"listing"}]""",
        )
        assertEquals(expected, pairing.pair(input))
    }

    @Test
    fun `a result of the wrong kind does not answer a call, so the call is answered aborted and the result dropped`() {
        val input = items(
            """[{"type":"custom_tool_call","call_id":"call_exec","name":"exec","input":"x"},
                {"type":"function_call_output","call_id":"call_exec","output":"wrong kind"}]""",
        )
        val expected = items(
            """[{"type":"custom_tool_call","call_id":"call_exec","name":"exec","input":"x"},
                {"type":"custom_tool_call_output","call_id":"call_exec","output":"aborted"}]""",
        )
        assertEquals(expected, pairing.pair(input))
    }

    @Test
    fun `a call sent twice goes out once, with the result it was sent with and no stand-in`() {
        // The 04:55 shape of Oct 27: a wait call written twice before its one output.
        val wait = """{"type":"function_call","id":"fc_wait","call_id":"call_wait","name":"wait",
            "arguments":"{\"cell_id\":\"1\"}"}"""
        val input = items(
            """[$wait,$wait,{"type":"function_call_output","call_id":"call_wait","output":"done"}]""",
        )
        val expected = items("""[$wait,{"type":"function_call_output","call_id":"call_wait","output":"done"}]""")
        assertEquals(expected, pairing.pair(input))
    }

    @Test
    fun `every result a call was sent with goes out, as a script's progress notification does`() {
        // Code mode answers one exec call with its script's output and then each notification the run queued, and a
        // client history that repeats a tool result repeats it. Codex's invariant is that a call HAS an output.
        val input = items(
            """[{"type":"custom_tool_call","call_id":"exec","name":"exec","input":"source"},
                {"type":"custom_tool_call_output","call_id":"exec","output":"Script completed"},
                {"type":"custom_tool_call_output","call_id":"exec","output":"progress"}]""",
        )
        assertEquals(input, pairing.pair(input))
    }

    @Test
    fun `a result sent before its call still answers it, so no stand-in is added and nothing moves`() {
        val input = items(
            """[{"type":"function_call_output","call_id":"call_1","output":"real result"},
                {"role":"user","content":"between"},
                {"type":"function_call","call_id":"call_1","name":"Read","arguments":"{}"}]""",
        )
        assertEquals(input, pairing.pair(input))
    }

    @Test
    fun `each reasoning item goes out once, where it first appears`() {
        // The record's copy of a reasoning item keeps the stream's empty content; the cache's copy has none. Same id.
        val input = items(
            """[{"type":"reasoning","id":"rs_1","encrypted_content":"e1","summary":[],"content":[]},
                {"type":"function_call","call_id":"call_1","name":"Read","arguments":"{}"},
                {"type":"function_call_output","call_id":"call_1","output":"ok"},
                {"type":"reasoning","id":"rs_1","encrypted_content":"e1","summary":[]},
                {"type":"reasoning","id":"rs_2","encrypted_content":"e2","summary":[]},
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}]""",
        )
        val expected = items(
            """[{"type":"reasoning","id":"rs_1","encrypted_content":"e1","summary":[],"content":[]},
                {"type":"function_call","call_id":"call_1","name":"Read","arguments":"{}"},
                {"type":"function_call_output","call_id":"call_1","output":"ok"},
                {"type":"reasoning","id":"rs_2","encrypted_content":"e2","summary":[]},
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}]""",
        )
        assertEquals(expected, pairing.pair(input))
    }

    @Test
    fun `parallel calls answered after both keep their order`() {
        val input = items(
            """[{"type":"function_call","call_id":"a","name":"Read","arguments":"{}"},
                {"type":"function_call","call_id":"b","name":"Read","arguments":"{}"},
                {"type":"function_call_output","call_id":"a","output":"A"},
                {"type":"function_call_output","call_id":"b","output":"B"}]""",
        )
        assertEquals(input, pairing.pair(input))
    }
}
