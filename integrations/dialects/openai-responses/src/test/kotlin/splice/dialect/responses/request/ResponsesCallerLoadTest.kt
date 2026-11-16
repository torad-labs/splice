// Goal (muse-tool-cache, muse-tool-load): on a head whose backend caches by prefix with the tool list before the input,
// a tool Claude Code's ToolSearch loads leaves the request's tool list as the conversation's first request sent it, and
// is written into the history where it was loaded, in the same bytes on every later request. The model calls it through
// the head's caller, and the call Claude Code sends back goes upstream the way the model made it. Anyone can tell by
// reading the requests the head sends for one conversation as it grows.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesToolChanges
import splice.dialect.responses.ResponsesToolQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

private const val CALLER = "CallToolByName"

/** Claude Code's tool list: two eager tools, ToolSearch, and the deferred tools ToolSearch can load. */
private const val TOOLS = """
  {"name":"Bash","description":"Run a command",
   "input_schema":{"type":"object","properties":{"command":{"type":"string"}}}},
  {"name":"DeferredToolPlaceholder","description":"never loaded","defer_loading":true,
   "input_schema":{"type":"object","properties":{"x":{"type":"string"}}}},
  {"name":"SendMessage","description":"Send a message to a seat","defer_loading":true,
   "input_schema":{"type":"object","properties":{"to":{"type":"string"},"zq_body":{"type":"string"}},
   "required":["to","zq_body"]}},
  {"name":"TaskStop","description":"Stop a task","defer_loading":true,
   "input_schema":{"type":"object","properties":{"task_id":{"type":"string"}},"required":["task_id"]}},
  {"name":"ToolSearch","description":"Load deferred tools","input_schema":{"type":"object",
   "properties":{"query":{"type":"string"}}}}
"""

private const val OPENING = """
  {"role":"user","content":"Tell the lead the build is green."},
  {"role":"assistant","content":[{"type":"tool_use","id":"toolu_search","name":"ToolSearch",
   "input":{"query":"select:SendMessage"}}]}
"""

private const val SEARCH_RESULT = """
  {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_search",
   "content":[{"type":"tool_reference","tool_name":"SendMessage"}]}]}
"""

/** The same result as Claude Code sends it while it is the request's last block: with its cache mark. */
private const val SEARCH_RESULT_MARKED = """
  {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_search",
   "content":[{"type":"tool_reference","tool_name":"SendMessage","cache_control":{"type":"ephemeral"}}],
   "cache_control":{"type":"ephemeral"}}]}
"""

private const val SEND_CALL = """
  {"role":"assistant","content":[{"type":"tool_use","id":"toolu_send","name":"SendMessage",
   "input":{"to":"lead","zq_body":"green"}}]},
  {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_send","content":"sent"}]}
"""

private const val SECOND_LOAD = """
  {"role":"assistant","content":[{"type":"tool_use","id":"toolu_search2","name":"ToolSearch",
   "input":{"query":"select:TaskStop"}}]},
  {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_search2",
   "content":[{"type":"tool_reference","tool_name":"TaskStop"}]}]}
"""

class ResponsesCallerLoadTest {
    /** A profile that takes no inline tool change and calls an arrived tool through the caller, as Muse's does. */
    private val quirks = ResponsesQuirks(
        providerTag = "muse",
        lite = ResponsesLiteQuirks(),
        tools = ResponsesToolQuirks(toolChanges = ResponsesToolChanges.THROUGH_CALLER),
    )

    @Test
    fun `the tool list is the first request's, with the caller, before and after a load`() {
        val first = build(listOf("""{"role":"user","content":"Tell the lead the build is green."}"""))
        val loaded = build(listOf(OPENING, SEARCH_RESULT))
        val called = build(listOf(OPENING, SEARCH_RESULT, SEND_CALL, SECOND_LOAD))

        assertEquals(listOf("Bash", "ToolSearch", CALLER), listed(first), "deferred tools stay out until loaded")
        assertEquals(first.getValue("tools"), loaded.getValue("tools"), "a load leaves the tool list as it was")
        assertEquals(first.getValue("tools"), called.getValue("tools"), "so does a call and a second load")

        val caller = first.getValue("tools").jsonArray.map { it.jsonObject }.single { name(it) == CALLER }
        val schema = caller.getValue("parameters").jsonObject
        assertEquals(setOf("tool", "arguments"), schema.getValue("properties").jsonObject.keys)
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(setOf("tool", "arguments"), required.toSet())
        assertEquals("string", type(schema, "tool"))
        assertEquals("object", type(schema, "arguments"))
    }

    @Test
    fun `the loaded tool is written where it was loaded, in the same bytes whatever the cache mark does`() {
        val marked = build(listOf(OPENING, SEARCH_RESULT_MARKED))
        val later = build(listOf(OPENING, SEARCH_RESULT, SEND_CALL))

        val written = output(marked, "toolu_search")
        assertTrue("SendMessage" in written && CALLER in written, written)
        assertTrue("zq_body" in written && "Send a message to a seat" in written, "the schema and words ride: $written")
        assertEquals(written, output(later, "toolu_search"), "the load reads the same on a later request")
        assertEquals(input(marked), input(later).take(input(marked).size), "everything up to the load is a prefix")
        assertTrue(input(marked).none { "additional_tools" == it["type"]?.jsonPrimitive?.content })
    }

    @Test
    fun `the model's call of the loaded tool goes back through the caller, the same bytes every time`() {
        val once = build(listOf(OPENING, SEARCH_RESULT, SEND_CALL))
        val twice = build(listOf(OPENING, SEARCH_RESULT, SEND_CALL, SECOND_LOAD))

        val call = call(once, "toolu_send")
        assertEquals(CALLER, name(call))
        assertEquals("""{"tool":"SendMessage","arguments":{"to":"lead","zq_body":"green"}}""", arguments(call))
        assertEquals(call, call(twice, "toolu_send"))
        assertEquals(input(once), input(twice).take(input(once).size), "a second load only adds to the input")
    }

    @Test
    fun `a second load is written at its own place and both tools are called through the caller`() {
        val request = build(listOf(OPENING, SEARCH_RESULT, SEND_CALL, SECOND_LOAD))

        val second = output(request, "toolu_search2")
        assertTrue("TaskStop" in second && "task_id" in second, second)
        assertTrue("SendMessage" !in second, "the first load is not written again: $second")
        assertEquals(listOf("Bash", "ToolSearch", CALLER), listed(request))
    }

    @Test
    fun `a choice that forces a loaded tool forces the caller`() {
        val request = build(listOf(OPENING, SEARCH_RESULT), choice = """{"type":"tool","name":"SendMessage"}""")
        val choice = request.getValue("tool_choice").jsonObject
        assertEquals(CALLER, choice["name"]?.jsonPrimitive?.content)
    }

    private fun build(messages: List<String>, choice: String? = null): JsonObject {
        val forced = choice?.let { ""","tool_choice":$it""" }.orEmpty()
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"m","tools":[$TOOLS],"messages":[${messages.joinToString(",")}]$forced}""",
        )
        val options = BuildOptions(
            compact = false,
            models = ModelIds(original = "claude-muse--muse-wonder-full-1.0", upstream = "muse-wonder-full-1.0"),
            reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
            handoff = ReasoningHandoff(replay = InjectPriorReasoning(false), decode = { null }),
        )
        return ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options).req
    }
}

private fun input(request: JsonObject): List<JsonObject> = request.getValue("input").jsonArray.map { it.jsonObject }

private fun listed(request: JsonObject): List<String> = request.getValue("tools").jsonArray.map { name(it.jsonObject) }

private fun name(item: JsonObject): String = item.getValue("name").jsonPrimitive.content

private fun arguments(item: JsonObject): String = item.getValue("arguments").jsonPrimitive.content

private fun type(schema: JsonObject, property: String): String? =
    schema.getValue("properties").jsonObject.getValue(property).jsonObject["type"]?.jsonPrimitive?.content

private fun call(request: JsonObject, id: String): JsonObject = input(request).single {
    it["type"]?.jsonPrimitive?.content == "function_call" && it["call_id"]?.jsonPrimitive?.content == id
}

/** The text of the function_call_output answering [id], however it rides. */
private fun output(request: JsonObject, id: String): String = input(request).single {
    it["type"]?.jsonPrimitive?.content == "function_call_output" && it["call_id"]?.jsonPrimitive?.content == id
}.getValue("output").toString()
