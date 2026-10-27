package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.dialect.responses.request.responsesRequestJson

class ResponsesWsPrefillTest {
    @Test
    fun `prefill retains only ordered instruction and code-mode tool prefix`() {
        val full = parse(
            """{"model":"gpt-6.1-sol","reasoning":{"effort":"high"},"include":["reasoning.encrypted_content"],"input":[
                {"type":"message","role":"developer","content":[{"type":"input_text","text":"guide"}]},
                {"type":"additional_tools","tools":[{"name":"exec","description":"manual"}]},
                {"type":"message","role":"user","content":[{"type":"input_text","text":"private task"}]}
            ]}""",
        )

        val prefill = ResponsesWsPrefill.request(full)!!

        val items = prefill["input"]!!.jsonArray
        assertEquals(2, items.size)
        assertEquals(
            "guide",
            items[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("additional_tools", items[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(false, prefill["generate"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(full["model"], prefill["model"])
        assertEquals(full["reasoning"], prefill["reasoning"])
        assertEquals(full["include"], prefill["include"])
        assertFalse(items.toString().contains("private task"))
    }

    @Test
    fun `requests without reusable instructions or tools do not warm up`() {
        val ordinary = parse("""{"model":"m","input":[{"type":"message","role":"user","content":"task"}]}""")
        val emptyTools = parse(
            """{"model":"m","input":[{"type":"message","role":"user","content":"task"}],"tools":[]}""",
        )
        assertNull(ResponsesWsPrefill.request(ordinary))
        assertNull(ResponsesWsPrefill.request(emptyTools))
    }

    @Test
    fun `ordinary Responses instructions and function tools can warm up without an executor`() {
        val full = parse(
            """{"model":"generic","instructions":"shared guide","tools":[
                {"type":"function","name":"lookup","parameters":{"type":"object"}}],
                "previous_response_id":"obsolete","input":[{"role":"user","content":"private task"}]}""",
        )

        val prefill = requireNotNull(ResponsesWsPrefill.request(full)) {
            "a public instruction and function-tool prefix must be eligible for generate:false"
        }
        assertTrue(prefill.getValue("input").jsonArray.isEmpty())
        assertEquals(full["instructions"], prefill["instructions"])
        assertEquals(full["tools"], prefill["tools"])
        assertEquals("false", prefill.getValue("generate").jsonPrimitive.content)
        assertFalse("previous_response_id" in prefill)
        assertFalse(prefill.toString().contains("private task"))

        val session = ResponsesWsSession()
        val warm = session.frameAndEpoch("ordinary", prefill, 1)
        session.completed("ordinary", ResponsesWsIdentity.PendingCommit(prefill, 1, warm.epoch), "warm_public")
        val generated = parse(
            session.frameAndEpoch("ordinary", JsonObject(full - "previous_response_id"), 1).frame.json,
        )
        assertEquals("warm_public", generated.getValue("previous_response_id").jsonPrimitive.content)
        assertEquals(full["input"], generated["input"])
        assertFalse("generate" in generated)
    }

    @Test
    fun `generated public prefixes preserve declarations and never include task history`() {
        val random = kotlin.random.Random(26)
        repeat(96) { trial ->
            val topInstructions = trial and 1 != 0
            val topTools = trial and 2 != 0
            val messageInstructions = trial and 4 != 0
            val guide = JsonPrimitive("guide \"$trial\"\n${random.nextLong()} 🧭")
            val expectedPrefix = if (messageInstructions) {
                listOf(JsonObject(mapOf("role" to JsonPrimitive("developer"), "content" to guide)))
            } else {
                emptyList()
            }
            val input = expectedPrefix + listOf(
                JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive("private $trial"))),
                JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive("later $trial"))),
            )
            val full = JsonObject(
                buildMap {
                    put("model", JsonPrimitive("generic"))
                    put("input", JsonArray(input))
                    put("previous_response_id", JsonPrimitive("obsolete"))
                    if (topInstructions) put("instructions", guide)
                    if (topTools) {
                        put(
                            "tools",
                            JsonArray(listOf(publicFunctionTool("lookup_$trial"))),
                        )
                    }
                },
            )
            val prefill = ResponsesWsPrefill.request(full)
            if (listOf(topInstructions, topTools, messageInstructions).none { it }) {
                assertNull(prefill)
            } else {
                val prefix = requireNotNull(prefill) { "public reusable prefix missing on case $trial" }
                assertEquals(JsonArray(expectedPrefix), prefix["input"])
                assertEquals(full["instructions"], prefix["instructions"])
                assertEquals(full["tools"], prefix["tools"])
                assertFalse("previous_response_id" in prefix)
                assertEquals("false", prefix.getValue("generate").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `clean completed no output is required to accept a prefill id`() {
        assertEquals(
            "resp_1",
            ResponsesWsPrefill.responseIdIfCleanTerminal(
                parse(
                    """{"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[]}}""",
                ),
            ),
        )
        assertNull(
            ResponsesWsPrefill.responseIdIfCleanTerminal(
                parse(
                    """{"type":"response.completed","response":{"id":"resp_2","status":"completed",
                        "output":[{"type":"message"}]}}""",
                ),
            ),
        )
        assertTrue(
            ResponsesWsPrefill.isOutputEvent(parse("""{"type":"response.output_item.added"}""")),
        )
    }

    @Test
    fun `successful prefix warmup seeds normal delta frame without exposing generate flag`() {
        val full = parse(
            """{"model":"gpt-6.1-sol","input":[{"type":"message","role":"developer","content":"guide"},
                {"type":"additional_tools","tools":[{"name":"exec"}]},
                {"type":"message","role":"user","content":"task"}]}""",
        )
        val prefill = ResponsesWsPrefill.request(full)!!
        val session = ResponsesWsSession()
        val warm = session.frameAndEpoch("conv", prefill, 4)
        val warmCommit = ResponsesWsIdentity.PendingCommit(prefill, 4, warm.epoch)
        session.completed("conv", warmCommit, "resp_warm")

        val generated = session.frameAndEpoch("conv", full, 4).frame
        val sent = parse(generated.json)

        assertTrue(generated.chained)
        assertEquals("resp_warm", sent["previous_response_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("message"), sent["input"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertFalse("generate" in sent)
        assertEquals("task", sent["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
    }

    private fun publicFunctionTool(name: String): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("function"),
            "name" to JsonPrimitive(name),
            "parameters" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
            "strict" to JsonPrimitive(false),
        ),
    )

    private fun parse(text: String) = responsesRequestJson.parseToJsonElement(text).jsonObject
}
