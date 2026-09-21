package splice.dialect.responses.request

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ResponsesCompactionTailTest {

    private val json = Json
    private val tail = ResponsesCompactionTail()

    @Test
    fun `custom text is a final user input item and preserves every existing item byte for byte`() {
        val request = json.parseToJsonElement(
            """{"model":"wire","input":[{"role":"developer","content":"lite"},""" +
                """{"role":"user","content":[{"type":"input_text","text":"client summary"}]}],"tools":[]}""",
        ).jsonObject
        val before = request.getValue("input").jsonArray.map { it.toString() }

        val updated = tail.append(request, "retain decisions")
        val input = updated.getValue("input").jsonArray

        assertEquals(before, input.take(before.size).map { it.toString() })
        assertEquals("developer", input.first().jsonObject.getValue("role").jsonPrimitive.content)
        val appended = input.last().jsonObject
        assertEquals("user", appended.getValue("role").jsonPrimitive.content)
        val block = appended.getValue("content").jsonArray.single().jsonObject
        assertEquals("input_text", block.getValue("type").jsonPrimitive.content)
        assertEquals("retain decisions", block.getValue("text").jsonPrimitive.content)
        assertEquals(request.getValue("tools"), updated.getValue("tools"))
    }

    @Test
    fun `empty text and missing input preserve the original request instance`() {
        val request = json.parseToJsonElement("""{"input":[],"metadata":{"key":"value"}}""").jsonObject
        val malformed = json.parseToJsonElement("""{"model":"wire"}""").jsonObject

        assertSame(request, tail.append(request, ""))
        assertSame(malformed, tail.append(malformed, "custom"))
        assertEquals(JsonArray(emptyList()), request.getValue("input"))
    }
}
