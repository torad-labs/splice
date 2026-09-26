// NEW: V4-239 — GET /api/heads/{head}/wire: what `splice wire <head>` prints, read from the registry the
// heads are built into. The payload is the tap's own (the head's GET /wire serves the same); a head with
// no tap answers the head's own "tap is off" sentence and never an empty list; a rebuild that turns the
// tap off takes the old ring out of the registry.
package splice.head.wire

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.WallClock
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource

private const val HEAD = "claudex"

class WireRoutesTest {

    private val noCompaction = object : HeadCompactSource {
        override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
    }

    /** One configured head, [HEAD]; any other name is unknown. */
    private val heads = TurnsHeadLookup { name ->
        if (name == HEAD) listOf(TurnsHead(HEAD, noCompaction)) else emptyList()
    }

    private fun meta(session: String) = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-openrouter--m1",
        upstreamModel = "m1",
        clientMaxTokens = 8000,
        effort = "medium",
        summary = null,
        budgetTokens = null,
        sessionId = session,
    )

    /** A tap that kept three bodies, oldest first. */
    private fun tap(): WireTap {
        var now = 1_000L
        val tap = WireTap(keep = 5, now = WallClock { now++ })
        listOf("""{"n":0}""", """{"n":1}""", """{"n":2}""").forEach { tap.record(meta("s-1"), it) }
        return tap
    }

    private fun registry(tap: WireTap?) = WireTaps().apply { put(HEAD, tap) }

    @Test
    fun `serves the tap's own payload, the whole ring unless last is asked`() {
        val tap = tap()
        val routes = WireRoutes(heads) { registry(tap) }

        val all = routes.read(HEAD, null)
        assertEquals(HttpStatusCode.OK, all.status)
        assertEquals(tap.json(HEAD, 0), all.body)
        val bodies = Json.parseToJsonElement(all.body).jsonObject.getValue("records").jsonArray
            .map { it.jsonObject.getValue("body").jsonPrimitive.content }
        assertEquals(listOf("""{"n":0}""", """{"n":1}""", """{"n":2}"""), bodies)

        assertEquals(tap.json(HEAD, 1), routes.read(HEAD, "1").body)
        assertEquals(tap.json(HEAD, 0), routes.read(HEAD, " ").body, "a blank last is no last")
    }

    @Test
    fun `a head with no tap answers the head's own sentence, never an empty list`() {
        val off = WireRoutes(heads) { registry(null) }.read(HEAD, null)

        assertEquals(HttpStatusCode.Conflict, off.status)
        assertEquals(WIRE_TAP_OFF.replace("KEY", HEAD), off.body)
        assertTrue(off.body.contains("[heads.$HEAD.overrides] wireTap = N"), off.body)
    }

    @Test
    fun `a rebuild with the tap off takes the old ring out`() {
        val taps = registry(tap())
        taps.put(HEAD, null)

        assertEquals(HttpStatusCode.Conflict, WireRoutes(heads) { taps }.read(HEAD, null).status)
    }

    @Test
    fun `an unknown head, a bad count and an unwired registry each answer in words`() {
        val routes = WireRoutes(heads) { registry(tap()) }

        assertEquals(HttpStatusCode.BadRequest, routes.read("nope", null).status)
        assertTrue(routes.read("nope", null).body.contains("unknown head: nope"))
        listOf("0", "-2", "x").forEach { last ->
            val refused = routes.read(HEAD, last)
            assertEquals(HttpStatusCode.BadRequest, refused.status, last)
            assertTrue(refused.body.contains(BAD_LAST), refused.body)
        }
        val unwired = WireRoutes(heads) { null }.read(HEAD, null)
        assertEquals(HttpStatusCode.ServiceUnavailable, unwired.status)
        assertTrue(unwired.body.contains(WIRE_TAPS_UNWIRED), unwired.body)
    }
}
