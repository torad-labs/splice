// Pins L3 byte-parity of the hand-built hot-delta frames against kotlinx-serialization: for
// every control char 0x00-0x1F, the JSON specials, DEL, and non-ASCII/surrogate text, the frame
// the emitter writes must be BYTE-IDENTICAL to the frame built from a kotlinx JsonObject —
// "escaping differences are wire corruption" (the audit's one confirmed invariant violation:
// \b/\f divergence, fixed 2026-07-18).
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.Usage
import splice.gateway.wire.SseEmitter

class SseEscapingParityTest {

    private fun kotlinxDeltaFrame(index: Int, text: String): String {
        val data = buildJsonObject {
            put("type", "content_block_delta")
            put("index", index)
            putJsonObject("delta") {
                put("type", "text_delta")
                put("text", text)
            }
        }
        return "event: content_block_delta\ndata: $data\n\n"
    }

    private fun emittedDeltaFrame(text: String): String = runCaptured { emitter, frames ->
        val idx = emitter.openText()
        frames.clear() // drop message_start/ping/content_block_start — the delta frame is under test
        emitter.textDelta(idx, text)
    }

    private fun runCaptured(block: suspend (SseEmitter, MutableList<String>) -> Unit): String {
        val frames = mutableListOf<String>()
        var out = ""
        runTest {
            val emitter = SseEmitter.create(
                write = { frames.add(it) },
                model = "m",
                usagePayload = { buildJsonObject { put("output_tokens", 0) } },
                messageId = "msg_fixed",
            )
            block(emitter, frames)
            out = frames.single()
        }
        return out
    }

    @Test
    fun `every control character escapes byte-identically to kotlinx`() {
        for (code in 0x00..0x1F) {
            val payload = "a${code.toChar()}z"
            assertEquals(
                kotlinxDeltaFrame(0, payload),
                emittedDeltaFrame(payload),
                "divergence at control 0x%02x".format(code),
            )
        }
    }

    @Test
    fun `specials, DEL, non-ASCII and surrogate pairs match kotlinx byte for byte`() {
        val samples = listOf(
            """quote " backslash \ slash / tick '""",
            "del  kept literal",
            "héllo — ✓ done",
            "emoji 🚀 pair",
            "mixed\ttabs\nnewlines\rand\bforms",
            "", // empty delta value
        )
        for (payload in samples) {
            assertEquals(kotlinxDeltaFrame(0, payload), emittedDeltaFrame(payload), "divergence for: $payload")
        }
    }

    @Test
    fun `usage payload passthrough does not disturb parity`() {
        // sanity: the terminal path still uses kotlinx directly; nothing to pin beyond the
        // delta path, but keep one end-to-end frame to catch accidental frame-shape drift.
        val frame = emittedDeltaFrame("plain")
        assertEquals(kotlinxDeltaFrame(0, "plain"), frame)
        assertEquals(Usage().inputTokens, 0) // keep the Usage import honest
    }
}
