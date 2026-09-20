import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.prompt.SystemPromptMode
import splice.dialect.chat.ChatSystemPrompt

class ChatSystemPromptTest {

    private val json = Json
    private val prompt = ChatSystemPrompt()

    @Test
    fun `append inserts the prompt after the client system messages and before the first user`() {
        val request = messages(
            """{"role":"system","content":"house rules"}""",
            """{"role":"user","content":"hello"}""",
        )

        val updated = applied(request, SystemPromptMode.APPEND)

        assertEquals(listOf("system", "system", "user"), updated.map { role(it) })
        assertEquals("house rules", content(updated[0]))
        assertEquals("Be terse.", content(updated[1]))
        assertEquals("hello", content(updated[2]))
    }

    @Test
    fun `append leads the conversation when the client sent no system message`() {
        val request = messages(
            """{"role":"user","content":"hello"}""",
            """{"role":"assistant","content":"hi"}""",
        )

        val updated = applied(request, SystemPromptMode.APPEND)

        assertEquals(listOf("system", "user", "assistant"), updated.map { role(it) })
        assertEquals("Be terse.", content(updated[0]))
    }

    @Test
    fun `append trails a user-less conversation rather than inventing a position`() {
        val request = messages("""{"role":"assistant","content":"hi"}""")

        val updated = applied(request, SystemPromptMode.APPEND)

        assertEquals(listOf("assistant", "system"), updated.map { role(it) })
        assertEquals("Be terse.", content(updated[1]))
    }

    @Test
    fun `append leaves every client message byte-identical`() {
        val request = messages(
            """{"role":"system","content":"house rules"}""",
            """{"role":"user","content":"hello"}""",
            """{"role":"assistant","content":[{"type":"text","text":"hi"}]}""",
        )
        val before = request.getValue("messages").jsonArray.map { it.toString() }

        val after = applied(request, SystemPromptMode.APPEND)

        assertEquals(before[0], after[0].toString())
        assertEquals(before[1], after[2].toString())
        assertEquals(before[2], after[3].toString())
    }

    @Test
    fun `replace drops the client system messages instead of adding to them`() {
        val request = messages(
            """{"role":"system","content":"house rules"}""",
            """{"role":"system","content":"more rules"}""",
            """{"role":"user","content":"hello"}""",
        )

        val updated = applied(request, SystemPromptMode.REPLACE, "You are a bare model.")

        assertEquals(listOf("system", "user"), updated.map { role(it) })
        assertEquals("You are a bare model.", content(updated[0]))
        assertEquals("hello", content(updated[1]))
        assertFalse(updated.toString().contains("house rules"))
        assertFalse(updated.toString().contains("more rules"))
    }

    @Test
    fun `a request whose messages are absent and empty text both preserve the request`() {
        val request = json.parseToJsonElement("""{"model":"wire"}""").jsonObject
        val plain = messages("""{"role":"user","content":"hello"}""")

        assertSame(request, prompt.apply(request, "Be terse.", SystemPromptMode.APPEND))
        assertSame(request, prompt.apply(request, "Be terse.", SystemPromptMode.REPLACE))
        assertSame(plain, prompt.apply(plain, "", SystemPromptMode.APPEND))
    }

    // ── V4-170: strip ──

    private val hedges = "^IMPORTANT: Assist with authorized security testing"

    @Test
    fun `strip rewrites the client system message in place and leaves every other message byte-identical`() {
        val request = messages(
            """{"role":"system","content":"You are Claude Code.\n\nIMPORTANT: Assist with authorized security """ +
                """testing.\n\n# Harness\n - rules"}""",
            """{"role":"user","content":"hi"}""",
        )
        val user = request.getValue("messages").jsonArray[1].toString()

        val after = applied(request, SystemPromptMode.STRIP, hedges)

        assertEquals(2, after.size)
        assertEquals("You are Claude Code.\n\n# Harness\n - rules", content(after[0]))
        assertEquals(user, after[1].toString())
    }

    @Test
    fun `strip drops a system message stripped to nothing, and an untouched request stays the same instance`() {
        val only = messages(
            """{"role":"system","content":"IMPORTANT: Assist with authorized security testing."}""",
            """{"role":"user","content":"hi"}""",
        )
        val untouched = messages("""{"role":"system","content":"Be kind."}""", """{"role":"user","content":"hi"}""")

        assertEquals(listOf("user"), applied(only, SystemPromptMode.STRIP, hedges).map(::role))
        assertSame(untouched, prompt.apply(untouched, hedges, SystemPromptMode.STRIP))
    }

    private fun applied(
        request: JsonObject,
        mode: SystemPromptMode,
        text: String = "Be terse.",
    ) = prompt.apply(request, text, mode).getValue("messages").jsonArray

    private fun messages(vararg items: String): JsonObject =
        json.parseToJsonElement("""{"messages":[${items.joinToString(",")}]}""").jsonObject

    private fun role(message: JsonElement): String =
        message.jsonObject.getValue("role").jsonPrimitive.content

    private fun content(message: JsonElement): String =
        message.jsonObject.getValue("content").jsonPrimitive.content
}
