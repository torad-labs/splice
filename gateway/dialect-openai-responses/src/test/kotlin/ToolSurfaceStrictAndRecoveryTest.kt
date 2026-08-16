// Split from ToolSurfaceTest.kt (review 2026-07-25, PR top-level comments 6 + 8): that file already
// sat at detekt's TooManyFunctions ceiling (15 tests, thresholdInClasses: 15), so these two
// unrelated-but-related-to-the-same-round test groups — deferredToolObject's strict handling
// (comment 6) and dropToolSearchTool's multi-item stripping (comment 8) — get their own file
// rather than pushing ToolSurfaceTest.kt over the wall.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.wire.ToolDefinition
import splice.dialect.responses.ToolSurfaceRecovery
import splice.dialect.responses.ToolWireObjects

private val recovery = ToolSurfaceRecovery()

private val toolWire = ToolWireObjects()

class ToolSurfaceStrictAndRecoveryTest {

    // review 2026-07-25 (comment 1 + PR top-level comment 6): deferredToolObject shares
    // functionToolObject's exact strict-handling `when` (ToolSurface.kt), but rides the
    // tool_search ANSWER path, not the eager request path — untested by the fixture-pinned
    // ResponsesContractTest (its fixtures only exercise functionToolObject), so the
    // forceStrictFalse pass-through bug this round fixes could regress here unnoticed.
    @Test
    fun `deferredToolObject forces strict false under forceStrictFalse regardless of the tool's own value`() {
        val bash = ToolDefinition(name = "Bash", strict = true)
        val read = ToolDefinition(name = "Read") // strict = null
        val strictTrue = toolWire.deferredToolObject(bash, emitStrict = false, forceStrictFalse = true)
        val strictNull = toolWire.deferredToolObject(read, emitStrict = false, forceStrictFalse = true)
        assertEquals("false", strictTrue["strict"]?.jsonPrimitive?.content)
        assertEquals("false", strictNull["strict"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deferredToolObject passes through emitStrict true - unaffected by removing forceStrictFalse`() {
        val tool = ToolDefinition(name = "Bash", strict = true)
        val passthrough = toolWire.deferredToolObject(tool, emitStrict = true, forceStrictFalse = false)
        assertEquals("true", passthrough["strict"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deferredToolObject omits strict entirely when neither quirk is set`() {
        val tool = ToolDefinition(name = "Bash", strict = true)
        val omitted = toolWire.deferredToolObject(tool, emitStrict = false, forceStrictFalse = false)
        assertNull(omitted["strict"])
    }

    // review 2026-07-25 (PR top-level comment 8): markDanglingReasoning's backward walk
    // (ToolSurfaceRecovery.kt) is a `while`, not a single `if` — it keeps marking as long as the
    // PREVIOUS item is also reasoning, so a run of MULTIPLE contiguous reasoning items immediately
    // before a dropped tool_search_call should all be stripped, not just the one adjacent to the
    // call. Pinning what the code actually does (not what a single-hop fix would do).
    @Test
    fun `dropToolSearchTool strips the ENTIRE contiguous run of reasoning items, not just the nearest one`() {
        val body = """{"model":"gpt-5.6-sol","input":[
            {"type":"additional_tools","role":"developer","tools":[
                {"type":"function","name":"Bash","description":"d","parameters":{"type":"object","properties":{}}},
                {"type":"tool_search","execution":"client","description":"x",
                 "parameters":{"type":"object","properties":{}}}
            ]},
            {"role":"developer","content":"hi"},
            {"type":"reasoning","id":"rs_1","encrypted_content":"enc1"},
            {"type":"reasoning","id":"rs_2","encrypted_content":"enc2"},
            {"type":"tool_search_call","call_id":"ts_1","execution":"client","arguments":"{}"},
            {"type":"tool_search_output","call_id":"ts_1","status":"completed","execution":"client","tools":[]}
        ],"store":false,"stream":true}"""
        val stripped = recovery.dropToolSearchTool(body)
        assertTrue(stripped != null)
        val input = Json.parseToJsonElement(stripped!!).jsonObject["input"]!!.jsonArray
        assertEquals(2, input.size, "both reasoning items, the call, and the output are all gone")
        assertEquals("hi", input[1].jsonObject["content"]?.jsonPrimitive?.content, "unrelated item survives")
    }

    // review 2026-07-25 (PR top-level comment 8): markSearchCallOutputs/markDanglingReasoning both
    // scan the WHOLE input array unconditionally, so a SECOND round's reasoning+call+output should
    // strip exactly like the first, and a plain message BETWEEN the two rounds — never itself a
    // search item — must survive untouched and in its original relative position.
    @Test
    fun `dropToolSearchTool strips MULTIPLE search rounds - survivors keep their original order`() {
        val body = """{"model":"gpt-5.6-sol","input":[
            {"type":"additional_tools","role":"developer","tools":[
                {"type":"function","name":"Bash","description":"d","parameters":{"type":"object","properties":{}}},
                {"type":"tool_search","execution":"client","description":"x",
                 "parameters":{"type":"object","properties":{}}}
            ]},
            {"role":"developer","content":"hi"},
            {"type":"reasoning","id":"rs_1","encrypted_content":"enc1"},
            {"type":"tool_search_call","call_id":"ts_1","execution":"client","arguments":"{}"},
            {"type":"tool_search_output","call_id":"ts_1","status":"completed","execution":"client","tools":[]},
            {"role":"assistant","content":"between rounds"},
            {"type":"reasoning","id":"rs_2","encrypted_content":"enc2"},
            {"type":"tool_search_call","call_id":"ts_2","execution":"client","arguments":"{}"},
            {"type":"tool_search_output","call_id":"ts_2","status":"completed","execution":"client","tools":[]}
        ],"store":false,"stream":true}"""
        val stripped = recovery.dropToolSearchTool(body)
        assertTrue(stripped != null)
        val input = Json.parseToJsonElement(stripped!!).jsonObject["input"]!!.jsonArray
        // survivors: the additional_tools scaffold, "hi", and "between rounds" — IN THAT ORDER —
        // both full rounds (reasoning + call + output each) are gone.
        assertEquals(3, input.size, "both search rounds are fully stripped; only the 3 ordinary items remain")
        assertEquals(1, input[0].jsonObject["tools"]!!.jsonArray.size, "the tool_search TOOL entry is stripped")
        assertEquals("hi", input[1].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("between rounds", input[2].jsonObject["content"]?.jsonPrimitive?.content)
    }
}
