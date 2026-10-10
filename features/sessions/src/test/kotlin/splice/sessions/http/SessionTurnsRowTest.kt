// NEW: Oct 10, 2026 — the `turns` count on a session row, and the start that says what it covers.
//
// WHY THE PAIR IS THE WALL. Teams' "Add existing" menu draws "· N turns" beside each session so a
// person can tell two quiet sessions apart (fin, Oct 10). The number is a LOWER BOUND whenever the
// session began before the accumulator did, which happens on every daemon restart, so a row that
// published `turns` alone would say "12 turns" about a session that ran 400 and the menu would have
// no way to know. These arms pin that the two keys travel together, that a session nothing counted
// carries neither rather than a zero, and that the count is the session's own.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionTurnsRowTest {
    @TempDir lateinit var root: Path

    /** Counts only for the sessions [counted] names, as a head's accumulator does: it holds a total
     *  for each session it has tallied a row for, and nothing at all for the rest. */
    private fun rows(counted: Map<String, SessionTurnCount>): Map<String, JsonObject> {
        val rig = TeamRig(root)
        val routes = SessionsRoutes(
            rig.registry,
            TestTranscripts(),
            facts = SessionRowFacts(turnsOf = SessionTurnsOf { id -> counted[id] }),
        )
        return Json.parseToJsonElement(routes.sessionsJson()).jsonObject
            .getValue("sessions").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("session_id").jsonPrimitive.content }
    }

    @Test
    fun `a counted session carries its turns and the moment the count covers from`() {
        val listed = rows(mapOf(LEAD to SessionTurnCount(turns = 12, fromMs = AT - 5_000)))

        val lead = listed.getValue(LEAD)
        assertEquals(12L, lead.getValue("turns").jsonPrimitive.long)
        assertEquals(
            AT - 5_000,
            lead.getValue("turns_from_ms").jsonPrimitive.long,
            "the start rides with the count, so a reader can tell a whole figure from a floor",
        )
    }

    /** THE KEY A MENU DRAWS FROM. The comparison is made here because it needs both values in the same
     *  unit and the wire never says that `started_at` is milliseconds; a client guessing wrong draws a
     *  floor as a whole figure. The fixture's sessions are registered with `updatedAt` and no
     *  `startedAt`, so an unknown start is the case this arm lands on — and an unplaceable count reads
     *  partial, because it has not been shown to cover the session. */
    @Test
    fun `a count that cannot be placed against the session's start reads partial`() {
        val listed = rows(mapOf(LEAD to SessionTurnCount(turns = 12, fromMs = AT - 5_000)))

        assertTrue(
            listed.getValue(LEAD).getValue("turns_partial").jsonPrimitive.boolean,
            "no known session start means the count has not been shown to cover the whole session",
        )
    }

    /** And the comparison itself, on a count whose start is known, both ways round. */
    @Test
    fun `a count is whole when it began before the session and a floor when it began after`() {
        val began = AT - 60_000
        val whole = SessionTurnCount(turns = 9, fromMs = began - 1).partialFor(began)
        val floor = SessionTurnCount(turns = 9, fromMs = began + 1).partialFor(began)
        val exactly = SessionTurnCount(turns = 9, fromMs = began).partialFor(began)

        assertFalse(whole, "counting started before the session, so every one of its turns is in the figure")
        assertTrue(floor, "counting started after it, so the turns before that are missing from the figure")
        assertFalse(exactly, "counting started with it, which covers it: the boundary is not a floor")
    }

    /** THE ONE A MENU MUST NOT GET WRONG. Nothing counted this session, which is not the same fact as
     *  "it ran nothing": its rows may all predate this store's start. So the row claims neither. */
    @Test
    fun `a session nothing counted carries no turns key at all, rather than a zero`() {
        val listed = rows(mapOf(LEAD to SessionTurnCount(turns = 3, fromMs = AT)))

        val uncounted = listed.getValue(BUILDER)
        assertFalse(uncounted.containsKey("turns"), "an uncounted session must not read as a measured zero")
        assertFalse(uncounted.containsKey("turns_from_ms"), "and the start goes with it: half a pair says nothing")
        assertFalse(uncounted.containsKey("turns_partial"), "nor a floor flag about a count that does not exist")
        assertTrue(listed.getValue(LEAD).containsKey("turns"), "while the counted session on the same listing has it")
    }

    /** A real zero is still publishable: the accumulator answered for this session and the answer was
     *  none. That is a fact about the session, unlike an absent total, and the menu may draw it. */
    @Test
    fun `an accumulator that answers zero publishes the zero`() {
        val listed = rows(mapOf(LEAD to SessionTurnCount(turns = 0, fromMs = AT - 1)))

        assertEquals(0L, listed.getValue(LEAD).getValue("turns").jsonPrimitive.long)
        assertEquals(AT - 1, listed.getValue(LEAD).getValue("turns_from_ms").jsonPrimitive.long)
    }

    @Test
    fun `each row carries its own count and never another session's`() {
        val listed = rows(
            mapOf(
                LEAD to SessionTurnCount(turns = 12, fromMs = AT),
                BUILDER to SessionTurnCount(turns = 400, fromMs = AT - 99),
            ),
        )

        assertEquals(12L, listed.getValue(LEAD).getValue("turns").jsonPrimitive.long)
        assertEquals(400L, listed.getValue(BUILDER).getValue("turns").jsonPrimitive.long)
        assertFalse(listed.getValue(OUTSIDER).containsKey("turns"), "and a session with no count has none")
    }

    /** The default wiring: a control plane built without an accumulator leaves every row alone. */
    @Test
    fun `a control plane with no accumulator wired leaves every row without turns`() {
        val rig = TeamRig(root)
        val routes = SessionsRoutes(rig.registry, TestTranscripts())
        val listed = Json.parseToJsonElement(routes.sessionsJson()).jsonObject
            .getValue("sessions").jsonArray.map { it.jsonObject }

        assertTrue(listed.isNotEmpty(), "the fixture has sessions to list")
        assertTrue(listed.none { it.containsKey("turns") }, "no accumulator, no claim about anyone's turns")
    }
}
