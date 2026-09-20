// NEW: V4-159 — team member check state has a real daemon source: TeamsEconomics.kt's header says
// what "a check" is (the outcome tag of a slot's most recently tallied turn) and where it is read
// from (PerfRow.outcome, the same perf rows already tallied here for tokens and cost). These tests
// prove pass, fail, the honest empty with no turns at all, and that "most recent" is by TIMESTAMP,
// not by the order rows were added — the same robustness lastAt already carries.
package console.v4159

import console.v4131.TeamRig
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.PerfRow
import splice.control.api.TeamEconomics
import splice.core.perf.OutcomeTag
import splice.core.teams.Team
import splice.core.teams.TeamSlot
import java.nio.file.Path

private const val AT = 1_800_000_000_000L
private const val LEAD = "e5e5e5e5-0000-4000-8000-000000000009"
private const val CHECKS_SOURCE = "the outcome tag of the slot's most recently tallied turn (PerfRow.outcome)"
private const val NO_TURNS_SOURCE = "no turns recorded yet for this slot"

class TeamsEconomicsChecksTest {

    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }

    private fun team() = Team(
        name = "atlas",
        goal = "ship",
        repo = "/r",
        slots = listOf(TeamSlot(id = "lead", role = "lead", head = "claude", session = LEAD)),
    )

    private fun row(ts: Long, outcome: String) = PerfRow(
        ts = ts,
        outcome = outcome,
        fields = mapOf("in_tokens" to 1L),
        session = LEAD.take(8),
    )

    /** The lead slot's object from a fresh [TeamEconomics] read over [rows]. */
    private fun leadSlot(rows: List<PerfRow>) = TeamEconomics(team(), mapOf("claude" to rig.head("claude", rows)))
        .json().getValue("slots").jsonArray.single().jsonObject

    @Test
    fun `no turns at all is the honest empty, never a fabricated pass`() {
        val lead = leadSlot(emptyList())
        assertEquals("null", lead.getValue("checks").toString())
        assertEquals(NO_TURNS_SOURCE, lead.getValue("checks_source").jsonPrimitive.content)
    }

    @Test
    fun `an ok outcome reads pass`() {
        val lead = leadSlot(listOf(row(AT, OutcomeTag.OK.wire)))
        assertEquals("pass", lead.getValue("checks").jsonPrimitive.content)
        assertEquals(CHECKS_SOURCE, lead.getValue("checks_source").jsonPrimitive.content)
    }

    @Test
    fun `a non-ok outcome reads fail, never null and never silently dropped`() {
        val lead = leadSlot(listOf(row(AT, OutcomeTag.RATE_LIMITED.wire)))
        assertEquals("fail", lead.getValue("checks").jsonPrimitive.content)
        assertEquals(CHECKS_SOURCE, lead.getValue("checks_source").jsonPrimitive.content)
    }

    @Test
    fun `checks follows the newest turn by timestamp, not the last one added`() {
        // The failing row is appended SECOND but carries the EARLIER timestamp: a naive
        // last-call-wins reading would report fail here. The slot's real newest turn (AT) is ok.
        val lead = leadSlot(listOf(row(AT, OutcomeTag.OK.wire), row(AT - 1_000, OutcomeTag.UNEXPECTED.wire)))
        assertEquals("pass", lead.getValue("checks").jsonPrimitive.content)
    }

    @Test
    fun `a later failure overrides an earlier pass`() {
        val lead = leadSlot(listOf(row(AT - 1_000, OutcomeTag.OK.wire), row(AT, OutcomeTag.CLIENT_ABORT.wire)))
        assertEquals("fail", lead.getValue("checks").jsonPrimitive.content)
    }
}
