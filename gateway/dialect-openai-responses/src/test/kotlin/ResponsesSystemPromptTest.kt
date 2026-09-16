import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.prompt.SystemPromptMode
import splice.dialect.responses.ResponsesSystemPrompt

class ResponsesSystemPromptTest {

    private val json = Json
    private val prompt = ResponsesSystemPrompt()

    @Test
    fun `append adds one trailing developer item and leaves the client input byte-identical`() {
        val request = json.parseToJsonElement(
            """{"instructions":"house rules",""" +
                """"input":[{"role":"user","content":[{"type":"input_text","text":"hi"}]}]}""",
        ).jsonObject
        val before = request.getValue("input").jsonArray.map { it.toString() }

        val updated = prompt.apply(request, "Be terse.", SystemPromptMode.APPEND)
        val input = updated.getValue("input").jsonArray

        assertEquals(before, input.dropLast(1).map { it.toString() })
        assertEquals("developer", input.last().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("Be terse.", input.last().jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("house rules", updated.getValue("instructions").jsonPrimitive.content)
    }

    @Test
    fun `append leaves lite's leading developer item alone and adds a second one at the tail`() {
        val request = json.parseToJsonElement(
            """{"input":[{"role":"developer","content":"client instructions"},""" +
                """{"role":"user","content":[{"type":"input_text","text":"hi"}]}]}""",
        ).jsonObject

        val input = prompt.apply(request, "Be terse.", SystemPromptMode.APPEND)
            .getValue("input").jsonArray

        val clientItem = input[1].jsonObject
        val clientText = clientItem.getValue("content").jsonArray.single().jsonObject

        assertEquals(3, input.size)
        assertEquals("client instructions", input[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("hi", clientText.getValue("text").jsonPrimitive.content)
        assertEquals("Be terse.", input[2].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `replace rewrites the top level instructions the non-lite shape rides`() {
        val request = json.parseToJsonElement(
            """{"instructions":"house rules","input":[{"role":"user","content":"hi"}],"tool_choice":"auto"}""",
        ).jsonObject

        val updated = prompt.apply(request, "You are a bare model.", SystemPromptMode.REPLACE)

        assertEquals("You are a bare model.", updated.getValue("instructions").jsonPrimitive.content)
        assertEquals("auto", updated.getValue("tool_choice").jsonPrimitive.content)
        val clientItem = updated.getValue("input").jsonArray.single().jsonObject
        assertEquals("hi", clientItem.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `replace on a lite turn rewrites the developer item, additional_tools untouched`() {
        val request = json.parseToJsonElement(
            """{"input":[{"type":"additional_tools","role":"developer","tools":[{"name":"t"}]},""" +
                """{"role":"developer","content":"client instructions"},""" +
                """{"role":"user","content":"hi"}]}""",
        ).jsonObject

        val input = prompt.apply(request, "You are a bare model.", SystemPromptMode.REPLACE)
            .getValue("input").jsonArray

        assertEquals("additional_tools", input[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("[{\"name\":\"t\"}]", input[0].jsonObject.getValue("tools").toString())
        assertEquals("You are a bare model.", input[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("hi", input[2].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `replace on a lite turn leaves codex's empty serde-parity field as it was`() {
        val request = json.parseToJsonElement(
            """{"instructions":"","input":[{"role":"developer","content":"client instructions"}]}""",
        ).jsonObject

        val updated = prompt.apply(request, "You are a bare model.", SystemPromptMode.REPLACE)

        assertEquals("", updated.getValue("instructions").jsonPrimitive.content)
        assertEquals(
            "You are a bare model.",
            updated.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content,
        )
    }

    @Test
    fun `an absent input and empty text both preserve the request`() {
        val request = json.parseToJsonElement("""{"model":"wire"}""").jsonObject
        val plain = json.parseToJsonElement("""{"input":[{"role":"user","content":"hi"}]}""").jsonObject

        assertSame(request, prompt.apply(request, "Be terse.", SystemPromptMode.APPEND))
        assertSame(request, prompt.apply(request, "Be terse.", SystemPromptMode.REPLACE))
        assertSame(plain, prompt.apply(plain, "", SystemPromptMode.REPLACE))
    }
}
