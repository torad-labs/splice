package splice.head.wire

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Path

private const val FLOOR = "trace volume free-space floor reached"

/** #518: Settings read "Saving stopped 6:37 PM" a minute after a restart at 6:37 PM, while nothing had been saved
 *  since 6:00 PM. The time the kept route serves is the first refusal's, whichever start is serving it. */
class TraceStopAcrossRestartTest {

    @Test
    fun `the time saving stopped is the same after a restart, until a request saves whole again`(@TempDir tmp: Path) {
        var now = 1_000L
        val file = TraceStopFile(tmp.resolve("trace").resolve("head.saving-stopped"), LogSink { })
        TraceSwitch(true, WallClock { now }, file).refused(FLOOR)

        now = 9_000L
        val restarted = TraceSwitch(true, WallClock { now }, file)
        restarted.refused(FLOOR)
        assertEquals(TraceStop(1_000L, FLOOR), restarted.stopped, "the first refusal's time, not this start's")

        restarted.saved()
        assertNull(TraceSwitch(true, WallClock { now }, file).stopped, "a request saved whole ends it for good")
    }
}
