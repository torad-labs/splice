// NEW: V4-159 — the team member `checks` field (TeamsEconomics.kt, features/sessions) reduces every
// perf-row outcome tag to pass/fail with OutcomeTags.isClean (splice.sessions.http.TeamsEconomics:
// `checksOf`). That reduction is only correct while OK's wire spelling is unique in the vocabulary;
// this proves it here, in core where the vocabulary is declared, so a future tag added to
// OutcomeTag.kt cannot silently collide with "ok" and turn a real failure into a reported pass
// without a red test naming it.
package splice.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OutcomeTagChecksTest {

    @Test
    fun `stopped tags are not failures and unfamiliar endings remain failures`() {
        val stopped = setOf(OutcomeTag.CLIENT_ABORT.wire, OutcomeTags.error("stopped"))
        val clean = setOf(OutcomeTag.OK.wire, OutcomeTag.EMPTY_MESSAGE.wire)
        val tags = OutcomeTag.entries.map { it.wire } +
            listOf(OutcomeTags.error("stopped"), "?", "error:new-ending", "failure:new-ending")
        tags.forEach { tag ->
            assertEquals(tag in stopped, OutcomeTags.isStopped(tag), tag)
            assertEquals(tag in clean, OutcomeTags.isClean(tag), tag)
            assertEquals(tag !in clean && tag != "?" && tag !in stopped, OutcomeTags.isFailed(tag), tag)
        }
    }

    @Test
    fun `an empty answer the model closed is a clean ending, never a failure`() {
        assertEquals(false, OutcomeTags.isFailed(OutcomeTag.EMPTY_MESSAGE.wire))
    }

    @Test
    fun `OK's wire spelling is unique, so a checks pass-fail comparison against it is unambiguous`() {
        val collisions = OutcomeTag.entries.filter { it != OutcomeTag.OK && it.wire == OutcomeTag.OK.wire }
        assertEquals(emptyList<OutcomeTag>(), collisions)
    }
}
