// NEW: (2026-09-02) the close line's clauses, against a scripted clock. A close that says only
// "status=1006" is the shape every argument about the transport was had over; each clause here is
// one question the operator asked and the log could not answer: which socket, how old, working or
// idle, when the server last spoke, when it last pinged, and how many siblings were open.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.util.ElapsedClock
import splice.dialect.responses.OpenSockets
import splice.dialect.responses.WsPulse

class WsPulseTest {
    private var now = 1_000_000L
    private var open = 7

    private fun pulse() = WsPulse("ws-cafe", OpenSockets { open }, ElapsedClock { now })

    @Test
    fun `a fresh idle socket that never heard a ping says exactly that`() {
        val p = pulse()
        now += 5_000
        assertEquals("ws-cafe age 5s, idle, last frame 5s ago, no server ping yet, open=7", p.describe())
    }

    @Test
    fun `a round in flight reads as mid-round with its own elapsed time`() {
        val p = pulse()
        now += 3_000
        p.roundStarted()
        now += 2_500
        p.frame()
        p.ping()
        now += 110_000
        assertEquals(
            "ws-cafe age 115s, mid-round 112s in, last frame 110s ago, last server ping 110s ago, open=7",
            p.describe(),
        )
    }

    @Test
    fun `the round ending returns the socket to idle and the ping clock keeps counting`() {
        val p = pulse()
        p.roundStarted()
        now += 40_000
        p.ping()
        p.roundEnded()
        open = 32
        now += 20_000
        assertEquals("ws-cafe age 60s, idle, last frame 60s ago, last server ping 20s ago, open=32", p.describe())
    }
}
