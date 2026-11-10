package splice.head.admission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.head.RefusingReading
import splice.core.util.LogSink
import splice.core.util.WallClock

/** 2026-11-09 audit: a head refusing every request reads degraded on /health and in daemon.log, and the next admitted
 *  turn clears it. */
class RefusalStreakTest {
    private val lines = mutableListOf<String>()
    private var now = 1_000L
    private val streak = RefusalStreak("claudex", LogSink { lines += it }, WallClock { now })
    private val declined = "claudex cannot carry a request field it does not recognize, so the turn was not sent"

    @Test
    fun `five refusals in a row for one cause read degraded, said once, and an admitted turn clears it`() {
        repeat(4) { streak.refused(declined, "the request carries `x`") }
        assertNull(streak.reading())
        assertTrue(lines.isEmpty(), "$lines")

        now = 9_000L
        repeat(3) { streak.refused(declined, "the request carries `x`") }

        assertEquals(RefusingReading("the request carries `x`: $declined", 7, 1_000L), streak.reading())
        assertEquals(1, lines.size, "$lines")
        assertTrue(lines.single().startsWith("[claudex] HEAD DEGRADED: 5 requests in a row refused"), lines.single())

        streak.admitted()

        assertNull(streak.reading())
        assertTrue(lines.last().startsWith("[claudex] head admits turns again after 7 refused in a row"), lines.last())
    }

    @Test
    fun `refusals for different causes do not add up to one cause`() {
        repeat(4) { streak.refused(declined, null) }
        streak.refused("claudex does not serve model x", null)
        repeat(4) { streak.refused(declined, null) }

        assertNull(streak.reading())
    }

    @Test
    fun `an admitted turn between refusals starts the run again`() {
        repeat(4) { streak.refused(declined, null) }
        streak.admitted()
        repeat(4) { streak.refused(declined, null) }

        assertNull(streak.reading())
        assertTrue(lines.isEmpty(), "$lines")
    }
}
