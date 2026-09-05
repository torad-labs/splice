// NEW (review gap C, 2026-07-23): terminal harvest must survive JSON nulls on the Responses object.
// A JsonNull free-form reasoning field must NOT suppress the summary fallback; a null output_text
// must NOT leak the literal string "null" onto the wire; and neither may inject spurious paragraph
// separators. harvestResponsesOutput is a pure function, so this pins the behavior directly.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.dialect.responses.ResponsesHarvest

private val harvest = ResponsesHarvest()

class HarvestedTest {

    private fun resp(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a null free-form reasoning field falls back to the summary parts`() {
        val h = harvest.harvestResponsesOutput(
            resp(
                """{"output":[{"type":"reasoning","content":null,
                   "summary":[{"type":"summary_text","text":"the plan"}]}]}""",
            ),
        )
        assertEquals("the plan", h.thinking)
        assertFalse(h.thinking.contains("null"), "JsonNull must never render as literal \"null\": ${h.thinking}")
    }

    @Test
    fun `a null output_text part is skipped and the real answer is retained`() {
        val h = harvest.harvestResponsesOutput(
            resp(
                """{"output":[{"type":"message","content":[
                   {"type":"output_text","text":null},
                   {"type":"output_text","text":"answer"}]}]}""",
            ),
        )
        assertEquals("answer", h.text)
        assertFalse(h.text.contains("null"), "a null text part must not leak \"null\": ${h.text}")
    }

    @Test
    fun `usage alias chain reads prompt_tokens and completion_tokens spellings`() {
        // CX-18 follow-up (review #94, F155): the translator fixtures all used the canonical
        // spellings, so the alias fallback was unpinned — a regression dropping it (or flipping
        // precedence) stayed green. usageFrom is a pure function; pin the chain directly.
        val u = harvest.usageFrom(resp("""{"usage":{"prompt_tokens":100,"completion_tokens":7}}"""))
        assertEquals(100, u.inputTokens)
        assertEquals(7, u.outputTokens)
    }

    @Test
    fun `canonical usage spelling wins when both spellings are present`() {
        val u = harvest.usageFrom(
            resp(
                """{"usage":{"input_tokens":100,"prompt_tokens":999,
                   "output_tokens":7,"completion_tokens":888}}""",
            ),
        )
        assertEquals(100, u.inputTokens)
        assertEquals(7, u.outputTokens)
    }

    @Test
    fun `two reasoning items join as one paragraph break with no spurious separators`() {
        // First item: null free-form → summary fallback. Second: empty free-form array → also
        // summary fallback. They must join with exactly one blank line, no leading/trailing breaks.
        val h = harvest.harvestResponsesOutput(
            resp(
                """{"output":[
                   {"type":"reasoning","content":null,"summary":[{"type":"summary_text","text":"first"}]},
                   {"type":"reasoning","content":[],"summary":[{"type":"summary_text","text":"second"}]}]}""",
            ),
        )
        assertEquals("first\n\nsecond", h.thinking)
    }

    @Test
    fun `describeOutput names every item type with the sizes that decide client visibility`() {
        val line = harvest.describeOutput(
            resp(
                """{"status":"completed","output":[
                   {"type":"reasoning","summary":[],"encrypted_content":"abcd"},
                   {"type":"message","content":[{"type":"output_text","text":""}]},
                   {"type":"function_call","name":"Bash","arguments":"{}"},
                   {"type":"agent_message","author":"a","recipient":"b","content":[]}]}""",
            ),
        )
        assertEquals(
            "status=completed items=[reasoning(summary=0,enc=4) message(output_text:0) function_call(Bash) agent_message(author,recipient,content)]",
            line,
        )
    }

    @Test
    fun `describeOutput says so when no terminal response was ever seen`() {
        assertEquals("status=<no terminal response>", harvest.describeOutput(null))
    }

    @Test
    fun `describeOutput appends the streamed item types when the terminal output is empty`() {
        val line = harvest.describeOutput(
            resp("""{"status":"completed","output":[]}"""),
            listOf("reasoning", "custom_tool_call"),
        )
        assertEquals("status=completed items=[] streamed=[reasoning,custom_tool_call]", line)
    }
}
