// The CLI half of the stop ladder (V4-74), pinned where its constants are declared (LAYOUT-01). App's
// DaemonStopBudgetTest orders the whole ladder — drain < head budget < cooperative cap < halt floor <
// graceful rung < systemd, and the spawner and SIGTERM rung past the floor — but the three CLI rungs
// moved to features/lifecycle, which app's tests cannot import. It carries them as documented bounds,
// and THIS test holds each bound to its source: a rung that moves reds here, a daemon link that moves
// past a rung reds there.
package splice.lifecycle.restart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.lifecycle.start.STARTUP_POLLS

class StopRungBudgetTest {

    /** Every CLI poller steps at 250ms (DaemonStop's and DaemonLaunch's own private intervals). */
    private val pollMs = 250L

    @Test
    fun `the CLI rungs are the bounds app's stop ladder is ordered against`() {
        assertEquals(60_000L, GRACEFUL_POLLS * pollMs, "the graceful rung, which the 57s halt floor sits inside")
        assertEquals(62_000L, SIGTERM_POLLS * pollMs, "the SIGTERM rung, which must wait past the 57s halt floor")
        assertEquals(62_000L, STARTUP_POLLS * pollMs, "the spawner's budget, which must outlast the 57s lock wait")
    }
}
