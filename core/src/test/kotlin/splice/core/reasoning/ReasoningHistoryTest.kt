package splice.core.reasoning

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ReasoningHistoryTest {
    @Test
    fun `text coalescing and a split UTF16 surrogate pair preserve history identity`() {
        val whole = ReasoningHistory().apply { text("assistant", "Before \uD83D\uDE80 after") }
        val split = ReasoningHistory().apply {
            text("assistant", "Before \uD83D")
            text("assistant", "\uDE80 after")
        }
        assertEquals(whole.key, split.key)
        assertEquals(whole.offset, split.offset)
    }

    @Test
    fun `changed tool arguments change identity while JSON key order does not`() {
        val original = ReasoningHistory().apply {
            block("assistant", "tool_use", Json.parseToJsonElement("""{"id":"same","input":{"a":1,"b":2}}"""))
        }
        val reordered = ReasoningHistory().apply {
            block("assistant", "tool_use", Json.parseToJsonElement("""{"input":{"b":2,"a":1},"id":"same"}"""))
        }
        val changed = ReasoningHistory().apply {
            block("assistant", "tool_use", Json.parseToJsonElement("""{"id":"same","input":{"a":9,"b":2}}"""))
        }
        assertEquals(original.key, reordered.key)
        assertNotEquals(original.key, changed.key)
    }
}
