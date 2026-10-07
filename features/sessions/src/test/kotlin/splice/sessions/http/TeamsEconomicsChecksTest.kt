// NEW: V4-159 — team member check state has a real daemon source: TeamsEconomics.kt's header says
// what "a check" is (the outcome tag of a slot's most recently tallied turn) and where it is read
// from (PerfRow.outcome, the same perf rows already tallied here for tokens and cost). These tests
// prove pass, fail, the honest empty with no turns at all, and that "most recent" is by TIMESTAMP,
// not by the order rows were added — the same robustness lastAt already carries.
package splice.sessions.http

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.TurnBill
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.turn.noRequestUsage
import splice.sessions.query.SessionPerfRow
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import java.nio.file.Path

private const val CHECKS_AT = 1_800_000_000_000L
private const val CHECKS_LEAD = "e5e5e5e5-0000-4000-8000-000000000009"
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
        slots = listOf(TeamSlot(id = "lead", role = "lead", head = "claude", session = CHECKS_LEAD)),
    )

    private fun row(ts: Long, outcome: String) = SessionPerfRow(
        ts = ts,
        outcome = outcome,
        fields = mapOf("in_tokens" to 1L),
        session = CHECKS_LEAD.take(8),
    )

    /** The lead slot's object from a fresh [TeamEconomics] read over [rows]. */
    private fun leadSlot(rows: List<SessionPerfRow>) =
        TeamEconomics(team(), mapOf("claude" to rig.head("claude", rows)))
            .json().getValue("slots").jsonArray.single().jsonObject

    @Test
    fun `a local code-mode step is not a team turn or a check`() {
        val step = row(CHECKS_AT, OutcomeTag.OK.wire).copy(fields = mapOf(PerfKeys.LOCAL_STEP to 1L))
        val onlyStep = leadSlot(listOf(step))
        assertEquals("0", onlyStep.getValue("turns").jsonPrimitive.content)
        assertEquals("null", onlyStep.getValue("checks").toString())
        val afterFailure = leadSlot(listOf(row(CHECKS_AT - 1, OutcomeTag.UNEXPECTED.wire), step))
        assertEquals("1", afterFailure.getValue("turns").jsonPrimitive.content)
        assertEquals("fail", afterFailure.getValue("checks").jsonPrimitive.content)
        val unknown = step.copy(session = null)
        val team = TeamEconomics(team(), mapOf("claude" to rig.head("claude", listOf(unknown)))).json()
        assertEquals("0", team.getValue("unattributed_turns").jsonPrimitive.content)
    }

    @Test
    fun `no turns at all is the honest empty, never a fabricated pass`() {
        val lead = leadSlot(emptyList())
        assertEquals("null", lead.getValue("checks").toString())
        assertEquals(NO_TURNS_SOURCE, lead.getValue("checks_source").jsonPrimitive.content)
    }

    @Test
    fun `a counted failure missing output usage is unpriced rather than a known zero bill`() {
        val lead = leadSlot(listOf(row(CHECKS_AT, OutcomeTag.UNEXPECTED.wire)))
        assertEquals("1", lead.getValue("unpriced_turns").jsonPrimitive.content)
        assertEquals("null", lead.getValue("cost_usd").toString())
    }

    @Test
    fun `a local refusal is a complete zero-cost team turn`() {
        val refusal = row(CHECKS_AT, OutcomeTag.RATE_LIMITED.wire).copy(
            fields = TurnBill.counters(noRequestUsage) + (PerfKeys.ATTEMPTS to 0L),
            model = "m",
        )
        val lead = leadSlot(listOf(refusal))
        assertEquals("1", lead.getValue("turns").jsonPrimitive.content)
        assertEquals("0", lead.getValue("unreported_usage_turns").jsonPrimitive.content)
        assertEquals("0", lead.getValue("unpriced_turns").jsonPrimitive.content)
        assertEquals(0.0, lead.getValue("cost_usd").jsonPrimitive.double)
    }

    @Test
    fun `a wholly unreported team turn labels its token totals as incomplete`() {
        val missing = row(CHECKS_AT, OutcomeTag.UNEXPECTED.wire).copy(fields = emptyMap())
        val lead = leadSlot(listOf(missing))
        assertEquals("1", lead["unreported_usage_turns"]?.jsonPrimitive?.content)
        assertEquals("0", lead.getValue("tokens").jsonObject.getValue("input").jsonPrimitive.content)
    }

    @Test
    fun `an ok outcome reads pass`() {
        val lead = leadSlot(listOf(row(CHECKS_AT, OutcomeTag.OK.wire)))
        assertEquals("pass", lead.getValue("checks").jsonPrimitive.content)
        assertEquals(CHECKS_SOURCE, lead.getValue("checks_source").jsonPrimitive.content)
    }

    @Test
    fun `a non-ok outcome reads fail, never null and never silently dropped`() {
        val lead = leadSlot(listOf(row(CHECKS_AT, OutcomeTag.RATE_LIMITED.wire)))
        assertEquals("fail", lead.getValue("checks").jsonPrimitive.content)
        assertEquals(CHECKS_SOURCE, lead.getValue("checks_source").jsonPrimitive.content)
    }

    @Test
    fun `an empty answer the model closed reads pass, because the client got a clean finish`() {
        val lead = leadSlot(listOf(row(CHECKS_AT, OutcomeTag.EMPTY_MESSAGE.wire)))
        assertEquals("pass", lead.getValue("checks").jsonPrimitive.content)
    }

    @Test
    fun `checks follows the newest turn by timestamp, not the last one added`() {
        // The failing row is appended SECOND but carries the EARLIER timestamp: a naive
        // last-call-wins reading would report fail here. The slot's real newest turn (CHECKS_AT) is ok.
        val lead = leadSlot(
            listOf(row(CHECKS_AT, OutcomeTag.OK.wire), row(CHECKS_AT - 1_000, OutcomeTag.UNEXPECTED.wire)),
        )
        assertEquals("pass", lead.getValue("checks").jsonPrimitive.content)
    }

    @Test
    fun `a later failure overrides an earlier pass`() {
        val lead = leadSlot(
            listOf(row(CHECKS_AT - 1_000, OutcomeTag.OK.wire), row(CHECKS_AT, OutcomeTag.CLIENT_ABORT.wire)),
        )
        assertEquals("fail", lead.getValue("checks").jsonPrimitive.content)
    }
}
