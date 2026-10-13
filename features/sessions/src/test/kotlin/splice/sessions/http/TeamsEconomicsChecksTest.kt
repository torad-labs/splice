// NEW: V4-159 — what a team slot counts: a local step is no turn, a failure with no usage has no price, and a
// refusal is a complete zero-cost turn.
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
    fun `a local code-mode step is not a team turn`() {
        val step = row(CHECKS_AT, OutcomeTag.OK.wire).copy(fields = mapOf(PerfKeys.LOCAL_STEP to 1L))
        val onlyStep = leadSlot(listOf(step))
        assertEquals("0", onlyStep.getValue("turns").jsonPrimitive.content)
        val afterFailure = leadSlot(listOf(row(CHECKS_AT - 1, OutcomeTag.UNEXPECTED.wire), step))
        assertEquals("1", afterFailure.getValue("turns").jsonPrimitive.content)
    }

    @Test
    fun `a counted failure missing output usage is unpriced rather than a known zero bill`() {
        val lead = leadSlot(listOf(row(CHECKS_AT, OutcomeTag.UNEXPECTED.wire)))
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
        assertEquals(0.0, lead.getValue("cost_usd").jsonPrimitive.double)
    }

    @Test
    fun `a refusal-only team needs no rate card to price zero`() {
        val refusal = row(CHECKS_AT, OutcomeTag.RATE_LIMITED.wire).copy(
            fields = TurnBill.counters(noRequestUsage),
            model = "m",
        )
        val head = rig.head("claude", listOf(refusal), rates = null)
        val lead = TeamEconomics(team(), mapOf("claude" to head))
            .json().getValue("slots").jsonArray.single().jsonObject
        assertEquals(0.0, lead.getValue("cost_usd").jsonPrimitive.double)
    }
}
