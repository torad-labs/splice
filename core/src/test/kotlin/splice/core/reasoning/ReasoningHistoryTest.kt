package splice.core.reasoning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ReasoningHistoryTest {
    @Test
    fun `installed block token vector preserves length prefixes beyond the encoding buffer`() {
        val history = ReasoningHistory().apply { block("user", "content", JsonPrimitive("x".repeat(8193))) }
        assertEquals(
            "splice-reasoning-at:1:b79a2085c347fea24cfb74f6d8b1fd34385fa29248e8e9281898a5425613e56f",
            history.key,
        )
    }

    @Test
    fun `installed history vectors preserve UTF16 units and snapshots across encoding boundaries`() {
        val unit = "A\u0000B\uD83D\uDE80\uD800x\uDC00"
        assertEquals(
            "splice-reasoning-at:8:37153a509bd70c1da28553202eab012317ee73dc5d4800ef430cbefc17e5285e",
            ReasoningHistory().apply { text("user", unit) }.key,
        )
        val text = unit.repeat(2049)
        for (size in listOf(1, 2, 7, 4095, 4096, 4097, 8191, text.length)) {
            val history = ReasoningHistory()
            text.chunked(size).forEach { history.text("user", it) }
            assertEquals(
                "splice-reasoning-at:16392:5819ebd5a3f34fbb930a7cde717f52119597ab326a9bf755664ca6fc7f28e292",
                history.key,
                "chunk size $size must retain the installed digest",
            )
            val branch = history.copy()
            branch.block(
                "assistant",
                "tool_use",
                Json.parseToJsonElement("""{"id":"call","input":{"n":1,"x":[true,null,"abc"]}}"""),
            )
            assertEquals(
                "splice-reasoning-at:16393:511ab49a65feb7914f0d064e66d8fb4d7918b6c2cf41d857fb41e49720c094f6",
                branch.key,
            )
            history.restore(branch)
            history.text("assistant", "Z\uDFFF")
            assertEquals(
                "splice-reasoning-at:16395:b5922f2abf14cafaf72cc728982721af4927f8120bd505e4d8b79c7eebd12be2",
                history.key,
            )
        }
    }

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
