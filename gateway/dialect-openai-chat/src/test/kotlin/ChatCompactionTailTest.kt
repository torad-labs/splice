import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.dialect.chat.ChatCompactionTail

class ChatCompactionTailTest {

    private val json = Json
    private val tail = ChatCompactionTail()

    @Test
    fun `custom text extends the final user string without changing earlier message bytes`() {
        val request = json.parseToJsonElement(
            """{"model":"wire","messages":[{"role":"system","content":"system"},""" +
                """{"role":"user","name":"client","content":"client summary"}],"tools":[]}""",
        ).jsonObject
        val before = request.getValue("messages").jsonArray.first().toString()

        val updated = tail.append(request, "retain decisions")

        assertEquals(
            """{"model":"wire","messages":[{"role":"system","content":"system"},""" +
                """{"role":"user","name":"client","content":"client summary\n\nretain decisions"}],"tools":[]}""",
            updated.toString(),
        )
        assertEquals(before, updated.getValue("messages").jsonArray.first().toString())
        assertEquals(request.getValue("tools"), updated.getValue("tools"))
    }

    @Test
    fun `array content retains every block and tool adjacency before the final text block`() {
        val request = json.parseToJsonElement(
            """{"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"call","type":"function",""" +
                """"function":{"name":"read","arguments":"{}"}}]},{"role":"tool","tool_call_id":"call",""" +
                """"content":"result"},{"role":"user","content":[{"type":"text","text":"client summary"},""" +
                """{"type":"image_url","image_url":{"url":"data:image/png;base64,aGVsbG8=","detail":"low"}}]}]}""",
        ).jsonObject
        val before = request.getValue("messages").jsonArray
        val content = before.last().jsonObject.getValue("content").jsonArray

        val updated = tail.append(request, "retain decisions")
        val after = updated.getValue("messages").jsonArray

        assertEquals(
            """{"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"call","type":"function",""" +
                """"function":{"name":"read","arguments":"{}"}}]},{"role":"tool","tool_call_id":"call",""" +
                """"content":"result"},{"role":"user","content":[{"type":"text","text":"client summary"},""" +
                """{"type":"image_url","image_url":{"url":"data:image/png;base64,aGVsbG8=","detail":"low"}},""" +
                """{"type":"text","text":"retain decisions"}]}]}""",
            updated.toString(),
        )
        assertEquals(before.dropLast(1).map { it.toString() }, after.dropLast(1).map { it.toString() })
        assertEquals(content.toList(), after.last().jsonObject.getValue("content").jsonArray.take(content.size))
    }

    @Test
    fun `assistant and empty message endings gain one final user message`() {
        val assistant = json.parseToJsonElement("""{"messages":[{"role":"assistant","content":"answer"}]}""")
            .jsonObject
        val empty = json.parseToJsonElement("""{"messages":[]}""").jsonObject

        assertEquals(
            """{"messages":[{"role":"assistant","content":"answer"},{"role":"user","content":"custom"}]}""",
            tail.append(assistant, "custom").toString(),
        )
        assertEquals(
            """{"messages":[{"role":"user","content":"custom"}]}""",
            tail.append(empty, "custom").toString(),
        )
    }

    @Test
    fun `empty user string receives only the custom text`() {
        val request = json.parseToJsonElement("""{"messages":[{"role":"user","content":""}]}""").jsonObject

        assertEquals(
            """{"messages":[{"role":"user","content":"custom"}]}""",
            tail.append(request, "custom").toString(),
        )
    }

    @Test
    fun `empty text and unsupported messages preserve the original request instance and bytes`() {
        val request = json.parseToJsonElement("""{"messages":[{"role":"user","content":"client summary"}]}""")
            .jsonObject
        val malformed = json.parseToJsonElement("""{"model":"wire"}""").jsonObject
        val nullContent = json.parseToJsonElement("""{"messages":[{"role":"user","content":null}]}""").jsonObject

        assertSame(request, tail.append(request, ""))
        assertEquals(request.toString(), tail.append(request, "").toString())
        assertSame(malformed, tail.append(malformed, "custom"))
        assertSame(nullContent, tail.append(nullContent, "custom"))
    }
}
