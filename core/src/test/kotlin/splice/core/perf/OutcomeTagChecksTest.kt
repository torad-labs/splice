// NEW: V4-159 — the team member `checks` field (TeamsEconomics.kt, gateway/control) reduces every
// perf-row outcome tag to pass/fail by comparing its wire spelling against OutcomeTag.OK.wire alone
// (splice.control.api.TeamsEconomics: `checksOf`). That reduction is only correct while OK's wire
// spelling is unique in the vocabulary; this proves it here, in core where the vocabulary is
// declared, so a future tag added to OutcomeTag.kt cannot silently collide with "ok" and turn a real
// failure into a reported pass without a red test naming it.
package splice.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OutcomeTagChecksTest {

    @Test
    fun `OK's wire spelling is unique, so a checks pass-fail comparison against it is unambiguous`() {
        val collisions = OutcomeTag.entries.filter { it != OutcomeTag.OK && it.wire == OutcomeTag.OK.wire }
        assertEquals(emptyList<OutcomeTag>(), collisions)
    }
}
