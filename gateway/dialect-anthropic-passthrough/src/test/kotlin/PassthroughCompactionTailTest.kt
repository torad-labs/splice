import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.dialect.passthrough.PassthroughCompactionTail

class PassthroughCompactionTailTest {

    private val json = Json
    private val tail = PassthroughCompactionTail()

    @Test
    fun `custom text is a block on the last user message with its existing prefix unchanged`() {
        val request = json.parseToJsonElement(
            """{"model":"wire","messages":[{"role":"user","content":[{"type":"text","text":"first"}]},""" +
                """{"role":"assistant","content":[{"type":"text","text":"answer"}]},""" +
                """{"role":"user","content":[{"type":"text","text":"client summary"}]}],"tools":[]}""",
        ).jsonObject
        val messagesBefore = request.getValue("messages").jsonArray
        val lastContentBefore = messagesBefore.last().jsonObject.getValue("content").jsonArray.map { it.toString() }

        val updated = tail.append(request, "retain decisions")
        val messages = updated.getValue("messages").jsonArray
        val content = messages.last().jsonObject.getValue("content").jsonArray

        assertEquals(messagesBefore.dropLast(1).map { it.toString() }, messages.dropLast(1).map { it.toString() })
        assertEquals(lastContentBefore, content.take(lastContentBefore.size).map { it.toString() })
        assertEquals("text", content.last().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("retain decisions", content.last().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(request.getValue("tools"), updated.getValue("tools"))
    }

    @Test
    fun `string content is retained as the first text block before the custom tail`() {
        val request = json.parseToJsonElement(
            """{"messages":[{"role":"user","content":"client summary"}]}""",
        ).jsonObject

        val content = tail.append(request, "custom").getValue("messages").jsonArray
            .single().jsonObject.getValue("content").jsonArray

        assertEquals("client summary", content.first().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("custom", content.last().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `empty text and absence of a user message preserve the original request instance`() {
        val request = json.parseToJsonElement("""{"messages":[]}""").jsonObject
        val assistantOnly = json.parseToJsonElement(
            """{"messages":[{"role":"assistant","content":"answer"}]}""",
        ).jsonObject

        assertSame(request, tail.append(request, ""))
        assertSame(assistantOnly, tail.append(assistantOnly, "custom"))
    }
}
