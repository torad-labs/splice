// NEW: V4-390 part 2 — the code-mode surface rides in the functions namespace. The dialect now hands
// the bridge a grouped lite list; the bridge reads the members, swaps in exec and groups the result.
package splice.provider.codex.v4390

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.CodeModeBridgeTestSupport

class CodeModeNamespaceSurfaceTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a grouped lite list becomes the functions namespace holding exec, with web_search beside it`() {
        val surface = surface(GROUPED)
        assertEquals(listOf("namespace", "web_search"), surface.map { it.getValue("type").jsonPrimitive.content })
        val members = surface[0].getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(listOf("exec"), members.map { it.getValue("name").jsonPrimitive.content })
        val manual = members[0].getValue("description").jsonPrimitive.content
        assertTrue(manual.contains("Reads a file."), manual)
    }

    @Test
    fun `a flat list and its grouped form produce the same surface`() {
        assertEquals(surface(GROUPED), surface(FLAT))
    }

    private fun surface(request: JsonObject): List<JsonObject> =
        bridge(ScriptedRuntime(ArrayDeque())).injectTool(request, setOf("Read"))
            .getValue("input").jsonArray[0].jsonObject.getValue("tools").jsonArray.map { it.jsonObject }

    private companion object {
        val GROUPED: JsonObject = Json.parseToJsonElement(
            """{"input":[{"type":"additional_tools","role":"developer","tools":[
            {"type":"namespace","name":"functions","description":"","tools":[
              {"type":"function","name":"Read","description":"Reads a file.","parameters":{"type":"object"}}]},
            {"type":"web_search"},{"type":"tool_search","execution":"client"}]},
            {"role":"developer","content":"base"},{"role":"user","content":"start"}]}""",
        ).jsonObject
        val FLAT: JsonObject = Json.parseToJsonElement(
            """{"input":[{"type":"additional_tools","role":"developer","tools":[
            {"type":"function","name":"Read","description":"Reads a file.","parameters":{"type":"object"}},
            {"type":"web_search"},{"type":"tool_search","execution":"client"}]},
            {"role":"developer","content":"base"},{"role":"user","content":"start"}]}""",
        ).jsonObject
    }
}
