// NEW (review gap C, 2026-07-23): terminal harvest must survive JSON nulls on the Responses object.
// A JsonNull free-form reasoning field must NOT suppress the summary fallback; a null output_text
// must NOT leak the literal string "null" onto the wire; and neither may inject spurious paragraph
// separators. harvestResponsesOutput is a pure function, so this pins the behavior directly.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.dialect.responses.harvestResponsesOutput

class HarvestedTest {

    private fun resp(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a null free-form reasoning field falls back to the summary parts`() {
        val h = harvestResponsesOutput(
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
        val h = harvestResponsesOutput(
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
    fun `two reasoning items join as one paragraph break with no spurious separators`() {
        // First item: null free-form → summary fallback. Second: empty free-form array → also
        // summary fallback. They must join with exactly one blank line, no leading/trailing breaks.
        val h = harvestResponsesOutput(
            resp(
                """{"output":[
                   {"type":"reasoning","content":null,"summary":[{"type":"summary_text","text":"first"}]},
                   {"type":"reasoning","content":[],"summary":[{"type":"summary_text","text":"second"}]}]}""",
            ),
        )
        assertEquals("first\n\nsecond", h.thinking)
    }
}
