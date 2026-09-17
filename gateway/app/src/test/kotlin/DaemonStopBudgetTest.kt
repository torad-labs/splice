// NEW (V4-74): the STOP LADDER, pinned as ONE ORDERING because raising a single link is DEAD CODE.
//
// The daemon's stop is a stack of nested deadlines, innermost first — each one cancels everything
// below it, so a link raised without the ones above it is not a longer stop, it is an unchanged one
// with a bigger number in it. That is exactly how this row's bug survived: STOP_DRAIN_NS and
// HEAD_STOP_BUDGET_MS were the only two the report named, and raising them alone would have done
// nothing, because Main's cooperative cap cancels the head stop at 8s and the CLI's graceful rung
// gives up at 11s. The whole ladder had to move together.
//
// MEASURED REASON the innermost link had to grow: a restart cancelled every in-flight turn, and the
// operator's deepseek turns run 7 to 16s.
//
// The drain figure itself lives in :gateway (HeadServer.STOP_DRAIN_NS), which this module cannot
// import, so it appears here as the documented bound the head budget must clear and is pinned at
// its source by HeadServerStopDrainTest. Two tests, one ladder, each half checked where it is
// visible — and if either number moves wrongly, ONE of the two reds.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.DaemonProcess
import splice.app.LOCK_POLL_INTERVAL_MS
import splice.app.LOCK_WAIT_POLLS
import splice.app.STOP_DEADLINE_MS
import splice.app.TEARDOWN_TAIL_GRACE_MS
import splice.app.cli.GRACEFUL_POLLS
import splice.app.cli.SIGTERM_POLLS
import splice.app.cli.STARTUP_POLLS
import splice.app.head.HEAD_STOP_BUDGET_MS

class DaemonStopBudgetTest {

    /** The innermost link, owned by :gateway's HeadServer. Kept in step with that constant by
     *  HeadServerStopDrainTest, which asserts its value directly where it is declared. */
    private val drainMs = 45_000L

    /** systemd's default TimeoutStopSec: the outer bound nothing in this process may reach. */
    private val systemdBoundMs = 90_000L

    private val haltFloorMs = STOP_DEADLINE_MS + TEARDOWN_TAIL_GRACE_MS

    /** ONE interval, taken from the constant this module exposes (DaemonLockWait), because all
     *  three pollers step at it: DaemonStop's and DaemonLaunch's own constants are private and stay
     *  that way — an internal interval in splice.app.cli clashes with the other one of the same
     *  name. Pinned to 250ms below so a change there reds here rather than silently rescaling three
     *  budgets. */
    private val pollMs = LOCK_POLL_INTERVAL_MS
    private val gracefulRungMs = GRACEFUL_POLLS * pollMs
    private val lockWaitMs = LOCK_WAIT_POLLS * pollMs
    private val spawnerMs = STARTUP_POLLS * pollMs
    private val sigtermRungMs = SIGTERM_POLLS * pollMs

    /** The whole ladder in ONE assertion chain, so a reader sees the shape before the failures. */
    @Test
    fun `the pollers all step at the interval these budgets are computed from`() {
        assertEquals(250L, pollMs, "the ladder's arithmetic assumes a 250ms poll interval")
    }

    @Test
    fun `the stop ladder descends from systemd to the drain, every link inside the next`() {
        assertTrue(
            drainMs < HEAD_STOP_BUDGET_MS,
            "the drain ($drainMs) must sit INSIDE the head budget ($HEAD_STOP_BUDGET_MS): " +
                "the drain owns the wait, so a budget below it cancels the very drain it exists for",
        )
        assertTrue(
            HEAD_STOP_BUDGET_MS < STOP_DEADLINE_MS,
            "the head budget ($HEAD_STOP_BUDGET_MS) must sit inside Main's cooperative cap " +
                "($STOP_DEADLINE_MS), or the cap cancels the head stop mid-drain",
        )
        assertTrue(
            STOP_DEADLINE_MS < haltFloorMs,
            "the cooperative cap must sit below its own halt floor by the grace window",
        )
        assertTrue(
            haltFloorMs < gracefulRungMs,
            "the halt floor ($haltFloorMs) must sit inside the CLI's graceful rung ($gracefulRungMs), " +
                "or a bounded stop is mistaken for a hung one and SIGTERM lands mid-tail",
        )
        assertTrue(
            gracefulRungMs < systemdBoundMs,
            "the CLI rung ($gracefulRungMs) must stay inside systemd's default TimeoutStopSec " +
                "($systemdBoundMs), which is the only bound this process cannot raise",
        )
    }

    /** The two polls that ALIAS the floor rather than sitting inside it, so a new daemon outwatts
     *  the old one's whole teardown instead of racing a daemon that is still serving. */
    @Test
    fun `the lock wait and the spawner both outlast the floor a restart has to wait for`() {
        assertEquals(
            haltFloorMs,
            lockWaitMs,
            "the lock poll IS the old daemon's teardown floor: a new daemon that gave up sooner " +
                "would race a process still draining in-flight turns",
        )
        assertTrue(
            spawnerMs > lockWaitMs,
            "the spawner's budget ($spawnerMs) must outlast the lock wait ($lockWaitMs), or it " +
                "reports a failure for a restart that is working",
        )
        assertTrue(
            sigtermRungMs > haltFloorMs,
            "the SIGTERM rung ($sigtermRungMs) must wait past the halt floor ($haltFloorMs), " +
                "which is the whole reason it is longer than the graceful rung",
        )
    }

    /** V4-74's other half: the daemon's ordered stop is the ONLY shutdown owner. Ktor registers its
     *  own JVM hook per engine, and it is disabled by a system property read ONCE at class-init —
     *  so this seams the boot call rather than racing hooks, and the ORDERING half (armed before the
     *  first engine exists) is structural: armShutdownOwnership is the first statement of
     *  runDaemon, ahead of the lock, the topology read and every engine. */
    @Test
    fun `the boot seam disables ktor's own shutdown hook`() {
        System.clearProperty("io.ktor.server.engine.ShutdownHook")
        DaemonProcess().armShutdownOwnership()
        assertEquals(
            "false",
            System.getProperty("io.ktor.server.engine.ShutdownHook"),
            "without this the engine's hook runs concurrently with the ordered stop and disposes the " +
                "application scope, which is what tore in-flight turns after content",
        )
    }
}
