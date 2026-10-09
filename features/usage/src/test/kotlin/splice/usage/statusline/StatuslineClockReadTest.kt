// NEW: the renderer reads the clock once per tick, so the session start the spend segment is handed and the
// instant the limit and warn segments are judged at come from the same moment, whichever segment asks first.
package splice.usage.statusline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.util.WallClock

private const val FIRST_READ_MS = 9_000L
private const val ELAPSED_MS = 1_000L

class StatuslineClockReadTest {
    @Test
    fun `an advancing clock is read once and the session start comes from that read`() {
        var reads = 0
        val clock = WallClock { FIRST_READ_MS + reads++ }
        var sessionStart: Long? = null
        val cost = SessionCostSource { _, _ -> null }
        val recording = object : SessionCostSource by cost {
            override fun spendFor(sessionId: String?, modelId: String?, sessionStartMs: Long?): SessionSpend? {
                sessionStart = sessionStartMs
                return SessionSpend(10.0, lowerBound = false)
            }
        }
        val renderer = StatuslineRenderer(label = "synthetic", now = clock, spend = StatuslineSpend(recording))

        renderer.render(
            """{"model":{"id":"synthetic"},"cost":{"total_duration_ms":$ELAPSED_MS}}""",
            null,
            StatuslineWarn(0, 0),
            sessionId = "synthetic",
        )

        assertEquals(1, reads, "one tick, one clock read")
        assertEquals(FIRST_READ_MS - ELAPSED_MS, sessionStart)
    }
}
