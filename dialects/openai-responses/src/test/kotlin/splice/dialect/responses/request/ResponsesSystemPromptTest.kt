package splice.dialect.responses.request

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.prompt.SystemPromptMode

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

    // ── V4-170: strip ──

    private val hedges = "^IMPORTANT: Assist with authorized security testing"

    @Test
    fun `strip on the non-lite shape edits the top level instructions in place`() {
        val request = json.parseToJsonElement(
            """{"instructions":"House rules.\n\nIMPORTANT: Assist with authorized security testing.\n\nBe kind.",""" +
                """"input":[{"role":"user","content":"hi"}],"tool_choice":"auto"}""",
        ).jsonObject

        val updated = prompt.apply(request, hedges, SystemPromptMode.STRIP)

        assertEquals("House rules.\n\nBe kind.", updated.getValue("instructions").jsonPrimitive.content)
        assertEquals(request.getValue("input").toString(), updated.getValue("input").toString())
    }

    @Test
    fun `strip on a lite turn edits the developer item where replace would - untouched stays the same instance`() {
        val request = json.parseToJsonElement(
            """{"input":[{"role":"developer","content":"IMPORTANT: Assist with authorized security testing.""" +
                """\n\nBe kind."},""" +
                """{"role":"user","content":[{"type":"input_text","text":"hi"}]}]}""",
        ).jsonObject
        val untouched = json.parseToJsonElement(
            """{"instructions":"Be kind.","input":[{"role":"user","content":"hi"}]}""",
        ).jsonObject

        val input = prompt.apply(request, hedges, SystemPromptMode.STRIP).getValue("input").jsonArray

        assertEquals(2, input.size)
        assertEquals("Be kind.", input[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(request.getValue("input").jsonArray[1].toString(), input[1].toString())
        assertSame(untouched, prompt.apply(untouched, hedges, SystemPromptMode.STRIP))
    }

    /** V4-172: the defect both reviews called critical. `isBaseInstructions` matches the shape
     *  `appended` builds, so an append-then-strip fold used to edit splice's OWN item and leave the
     *  client's `instructions` untouched. A strip edits BOTH. */
    @Test
    fun `strip edits the client instructions even when an append layer added a developer item first`() {
        val client = json.parseToJsonElement(
            """{"instructions":"House rules.\n\nIMPORTANT: Assist with authorized security testing.",""" +
                """"input":[{"role":"user","content":"hi"}]}""",
        ).jsonObject

        val afterAppend = prompt.apply(client, "Extra house rule.", SystemPromptMode.APPEND)
        val afterStrip = prompt.apply(afterAppend, hedges, SystemPromptMode.STRIP)

        assertEquals("House rules.", afterStrip.getValue("instructions").jsonPrimitive.content)
        val appended = afterStrip.getValue("input").jsonArray.last().jsonObject
        val kept = appended.getValue("content").jsonPrimitive.content
        assertEquals("Extra house rule.", kept, "the append layer survives")
    }

    @Test
    fun `an item stripped to nothing is dropped, not left empty`() {
        val request = json.parseToJsonElement(
            """{"input":[{"role":"developer","content":"IMPORTANT: Assist with authorized security testing."},""" +
                """{"role":"user","content":"hi"}]}""",
        ).jsonObject

        val input = prompt.apply(request, hedges, SystemPromptMode.STRIP).getValue("input").jsonArray

        assertEquals(1, input.size, "the emptied developer item is dropped, as the other seams drop theirs")
        assertEquals("user", input.single().jsonObject.getValue("role").jsonPrimitive.content)
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
