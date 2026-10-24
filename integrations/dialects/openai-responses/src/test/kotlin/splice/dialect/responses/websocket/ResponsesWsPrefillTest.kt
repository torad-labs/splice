package splice.dialect.responses.websocket

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
    fun `prefill rejects non code mode requests and empty prefix`() {
        val ordinary = parse("""{"model":"m","input":[{"type":"message","role":"user","content":"task"}]}""")
        val noPrefix = parse(
            """{"model":"m","input":[{"type":"additional_tools","tools":[{"name":"exec"}]},
                {"type":"message","role":"user","content":"task"}],"tools":[]}""",
        )
        assertNull(ResponsesWsPrefill.request(ordinary))
        assertNull(ResponsesWsPrefill.request(noPrefix))
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

    private fun parse(text: String) = responsesRequestJson.parseToJsonElement(text).jsonObject
}
