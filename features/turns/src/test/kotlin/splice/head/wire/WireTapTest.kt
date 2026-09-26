// NEW: V4-173 — the ring itself: bounded to what the operator named, oldest first, each record
// stamped from the turn's meta, and never buildable for "keep nothing" (a head with nothing to
// keep has no tap at all, so "off" and "empty" cannot be confused). The HTTP contract and the
// byte-equality with the upstream live in head/HeadWireTapTest.
package splice.head.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.WallClock

private fun meta(session: String?, compact: Boolean = false) = TurnMeta(
    compact = compact,
    showReasoning = ReasoningDisplay.TEXT,
    stream = true,
    originalModel = "claude-codex--gpt-5.6-sol",
    upstreamModel = "gpt-5.6-sol",
    clientMaxTokens = 8000,
    effort = "high",
    summary = "detailed",
    budgetTokens = null,
    sessionId = session,
)

class WireTapTest {

    @Test
    fun `keeps the last N bodies, oldest first`() {
        val tap = WireTap(keep = 2)
        tap.record(meta("s1"), """{"n":1}""")
        tap.record(meta("s1"), """{"n":2}""")
        tap.record(meta("s2"), """{"n":3}""")

        assertEquals(listOf("""{"n":2}""", """{"n":3}"""), tap.recent().map { it.body })
        assertEquals(listOf("""{"n":3}"""), tap.recent(last = 1).map { it.body })
        assertEquals(listOf("s1", "s2"), tap.recent().map { it.session })
    }

    @Test
    fun `a record carries the turn's stamp and the exact body string`() {
        var now = 1_000L
        val tap = WireTap(keep = 3, now = WallClock { now })
        tap.record(meta("abc", compact = true), "  {\"raw\": \"kept verbatim\"}\n")
        now = 2_000L
        tap.record(meta(null), "{}")

        val (first, second) = tap.recent()
        assertEquals(1_000L, first.ts)
        assertEquals("abc", first.session)
        assertEquals("gpt-5.6-sol", first.model)
        assertEquals(true, first.compact)
        assertEquals("  {\"raw\": \"kept verbatim\"}\n", first.body)
        assertEquals(2_000L, second.ts)
        assertNull(second.session)
        assertEquals(false, second.compact)
    }

    @Test
    fun `the json payload names the head, the ring size and every record`() {
        val tap = WireTap(keep = 5, now = WallClock { 7L })
        tap.record(meta("s9"), """{"model":"m"}""")

        val payload = Json.parseToJsonElement(tap.json("kimi")).jsonObject

        assertEquals("kimi", payload.getValue("key").jsonPrimitive.content)
        assertEquals("5", payload.getValue("keep").jsonPrimitive.content)
        val record = payload.getValue("records").jsonArray.single().jsonObject
        assertEquals("7", record.getValue("ts").jsonPrimitive.content)
        assertEquals("s9", record.getValue("session").jsonPrimitive.content)
        assertEquals("""{"model":"m"}""", record.getValue("body").jsonPrimitive.content)
    }

    @Test
    fun `a tap that keeps nothing cannot be built - off is null, never an empty ring`() {
        assertThrows(IllegalArgumentException::class.java) { WireTap(keep = 0) }
        assertThrows(IllegalArgumentException::class.java) { WireTap(keep = -1) }
    }
}
