// NEW: V4-159 — the team member `checks` field (TeamsEconomics.kt, features/sessions) reduces every
// perf-row outcome tag to pass/fail by comparing its wire spelling against OutcomeTag.OK.wire alone
// (splice.sessions.http.TeamsEconomics: `checksOf`). That reduction is only correct while OK's wire
// spelling is unique in the vocabulary; this proves it here, in core where the vocabulary is
// declared, so a future tag added to OutcomeTag.kt cannot silently collide with "ok" and turn a real
// failure into a reported pass without a red test naming it.
package splice.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OutcomeTagChecksTest {

    @Test
    fun `stopped tags are not failures and unfamiliar endings remain failures`() {
        val stopped = setOf(OutcomeTag.CLIENT_ABORT.wire, OutcomeTags.error("stopped"))
        val tags = OutcomeTag.entries.map { it.wire } +
            listOf(OutcomeTags.error("stopped"), "?", "error:new-ending", "failure:new-ending")
        tags.forEach { tag ->
            assertEquals(tag in stopped, OutcomeTags.isStopped(tag), tag)
            assertEquals(tag != OutcomeTag.OK.wire && tag != "?" && tag !in stopped, OutcomeTags.isFailed(tag), tag)
        }
    }

    @Test
    fun `OK's wire spelling is unique, so a checks pass-fail comparison against it is unambiguous`() {
        val collisions = OutcomeTag.entries.filter { it != OutcomeTag.OK && it.wire == OutcomeTag.OK.wire }
        assertEquals(emptyList<OutcomeTag>(), collisions)
    }
}
