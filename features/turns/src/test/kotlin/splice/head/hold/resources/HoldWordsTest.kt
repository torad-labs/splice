package splice.head.hold.resources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.head.turn.TurnState
import java.time.Instant
import java.time.ZoneOffset

/** What a turn held on a usage limit, an overload or credit says on its session's status line, as it has since
 *  slice 5 (docs/specs/harness.md, decision 8). */
class HoldWordsTest {
    private val words = HoldWords(ZoneOffset.UTC)
    private val reset = Instant.parse("2026-11-17T14:05:00Z")

    private fun held(cause: String, until: Instant?) = TurnState.Held(until, cause, "work", Instant.EPOCH, 0L)

    @Test
    fun `a plan limit says the reset it waits for, or a reset`() {
        assertEquals("⏸ plan limit reached: waiting until Tue 14:05 or a reset", words.of(held("limit", reset)))
        assertEquals("⏸ plan limit reached: waiting for a reset", words.of(held("limit", null)))
    }

    @Test
    fun `an overload and spent credit say what they wait for`() {
        assertEquals("⏸ provider overloaded: waiting until Tue 14:05", words.of(held("overload", reset)))
        assertEquals("⏸ provider overloaded: waiting for it to answer", words.of(held("overload", null)))
        assertEquals("⏸ out of credit: waiting for credit", words.of(held("credit", null)))
    }
}
