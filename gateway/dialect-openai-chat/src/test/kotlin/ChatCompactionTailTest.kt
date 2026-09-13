import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.dialect.chat.ChatCompactionTail

class ChatCompactionTailTest {

    private val json = Json
    private val tail = ChatCompactionTail()

    @Test
    fun `custom text is a final user message and preserves the message prefix byte for byte`() {
        val request = json.parseToJsonElement(
            """{"model":"wire","messages":[{"role":"system","content":"system"},""" +
                """{"role":"user","content":"client summary"}],"tools":[]}""",
        ).jsonObject
        val before = request.getValue("messages").jsonArray.map { it.toString() }

        val updated = tail.append(request, "retain decisions")
        val messages = updated.getValue("messages").jsonArray

        assertEquals(before, messages.take(before.size).map { it.toString() })
        assertEquals("user", messages.last().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("retain decisions", messages.last().jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(request.getValue("tools"), updated.getValue("tools"))
    }

    @Test
    fun `empty text and missing messages preserve the original request instance`() {
        val request = json.parseToJsonElement("""{"messages":[]}""").jsonObject
        val malformed = json.parseToJsonElement("""{"model":"wire"}""").jsonObject

        assertSame(request, tail.append(request, ""))
        assertSame(malformed, tail.append(malformed, "custom"))
    }
}
