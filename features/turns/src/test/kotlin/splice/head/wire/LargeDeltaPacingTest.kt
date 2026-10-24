package splice.head.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.TurnPerf
import splice.core.util.JsonScalars
import splice.core.util.JsonWire

private const val LARGE_CONTENT_REPEATS = 100
private const val PACING_DEADLINE_MS = 1_016L

class LargeDeltaPacingTest {
    private val stop = "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":7}\n\n"

    @Test
    fun `one large text or thinking delta reaches the client progressively before its block stop`() {
        for ((type, field) in listOf("text_delta" to "text", "thinking_delta" to "thinking")) {
            val value = ("a".repeat(31) + "😀\n\"\\").repeat(LARGE_CONTENT_REPEATS)
            val pacer = DeltaPacer().also { it.active = true }
            val perf = TurnPerf()
            assertTrue(pacer.hold(frame(type, field, value), perf, true, 0))
            assertTrue(pacer.hold(stop, perf, false, 0))
            val first = pacer.due(PACE_TICK_MS).map { it.frame }
            assertFalse(stop in first)
            val firstText = first.joinToString("") { text(it, field) }
            assertTrue(firstText.isNotEmpty() && firstText.length < value.length)
            val delivered = first.toMutableList()
            var now = PACE_TICK_MS
            while (pacer.stillHeld() && now < PACING_DEADLINE_MS) {
                now += PACE_TICK_MS
                delivered += pacer.due(now).map { it.frame }
            }
            assertEquals(stop, delivered.last())
            val deltas = delivered.dropLast(1)
            assertEquals(value, deltas.joinToString("") { text(it, field) })
            deltas.forEach {
                val data = payload(it)
                assertEquals(7, JsonScalars.int(data, "index"))
                assertEquals("kept", JsonScalars.str(data, "opaque"))
                val piece = text(it, field)
                assertFalse(piece.first().isLowSurrogate())
                assertFalse(piece.last().isHighSurrogate())
            }
        }
    }

    @Test
    fun `an emergency drain after partial delivery contains only the unread suffix`() {
        val value = "summary 😀\n".repeat(LARGE_CONTENT_REPEATS)
        val pacer = DeltaPacer().also { it.active = true }
        val perf = TurnPerf()
        assertTrue(pacer.hold(frame("thinking_delta", "thinking", value), perf, true, 0))
        assertTrue(pacer.hold(stop, perf, false, 0))
        val prefix = pacer.due(PACE_TICK_MS).map { it.frame }
        val rest = pacer.takeAll(PACING_DEADLINE_MS).map { it.frame }
        assertEquals(stop, rest.last())
        assertEquals(value, (prefix + rest.dropLast(1)).joinToString("") { text(it, "thinking") })
        assertFalse(pacer.stillHeld())
    }

    @Test
    fun `without an active pacing loop a large delta remains immediately writable`() {
        val value = "text".repeat(LARGE_CONTENT_REPEATS)
        assertFalse(DeltaPacer().hold(frame("text_delta", "text", value), TurnPerf(), true, 0))
    }

    private fun frame(type: String, field: String, value: String): String {
        val delta = buildJsonObject {
            put("type", type)
            put(field, value)
        }
        val data = buildJsonObject {
            put("type", "content_block_delta")
            put("index", 7)
            put("opaque", "kept")
            put("delta", delta)
        }
        return "event: content_block_delta\ndata: ${JsonWire.string(data)}\n\n"
    }

    private fun payload(frame: String): JsonObject = Json.parseToJsonElement(frame.substringAfter("data:")).jsonObject

    private fun text(frame: String, field: String): String =
        JsonScalars.str(payload(frame)["delta"] as JsonObject, field).orEmpty()
}
