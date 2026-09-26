// NEW: V4-137 — the supervision probe's two arms, and the number that rots silently.
//
// THE BUDGET PIN IS THE POINT OF THE SECOND TEST. The drain ladder is drain 45s < HEAD_STOP_BUDGET_MS
// 50s < STOP_DEADLINE_MS 55s, and DaemonStopBudgetTest already pins that ordering internally. What has
// never been pinned is the OUTERMOST link: the host unit's own stop timeout. systemd will SIGKILL the
// process when its TimeoutStopSec elapses, and it does not care that the daemon was mid-drain — the
// drain still LOOKS armed on the way out and simply stops draining. 55s against systemd's 90s default
// leaves 35s of headroom, and that headroom is the margin between "an orderly stop" and "a SIGKILL
// wearing an orderly stop's log line".
//
// THE CONSTANT BELOW IS systemd's DEFAULT, NOT A VALUE THE UNIT SETS — verified on this host with
// `systemctl --user show -p TimeoutStopUSec splice.service`, which reports 1min 30s while the unit file
// contains no TimeoutStopSec at all. So an operator who sets one in the unit makes this pin stale, and
// the pin cannot detect that: the host's unit configuration is not readable from this repo. It is
// written down here rather than implied, because a silent stale constant is exactly the failure this
// row exists to prevent, one level up.
package splice.app

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.console.DrainingRestartAdapter
import splice.app.console.INVOCATION_ID
import splice.app.head.HEAD_STOP_BUDGET_MS
import splice.core.util.EnvReader

/** systemd's default stop timeout, and the unit here does not override it. */
private const val SYSTEMD_STOP_TIMEOUT_MS = 90_000L

class DrainBudgetTest {

    @Test
    fun `the supervision probe answers from INVOCATION_ID and both ways`() {
        // The rig running this test was started by Gradle, not by a systemd unit, so the uninjected
        // read would pin whatever started the JVM and the supervised arm would be untestable. Both
        // arms are answered directly instead.
        assertTrue(
            DrainingRestartAdapter(EnvReader { if (it == INVOCATION_ID) "8c82ca38" else null })(),
            "a process systemd started is supervised — that is what INVOCATION_ID means",
        )
        assertTrue(
            !DrainingRestartAdapter(EnvReader { null })(),
            "a hand-started daemon is supervised by nothing, and the route must refuse it",
        )
        assertTrue(
            !DrainingRestartAdapter(EnvReader { if (it == INVOCATION_ID) "  " else null })(),
            "a blank value is not evidence of systemd: presence must be a real value, not an empty one",
        )
    }

    @Test
    fun `Main's cooperative cap fits inside the host unit's stop timeout`() {
        assertTrue(
            HEAD_STOP_BUDGET_MS < STOP_DEADLINE_MS,
            "the drain ladder must stay ordered: head budget $HEAD_STOP_BUDGET_MS under the cap",
        )
        assertTrue(
            STOP_DEADLINE_MS < SYSTEMD_STOP_TIMEOUT_MS,
            "STOP_DEADLINE_MS (${STOP_DEADLINE_MS}ms) must stay under systemd's stop timeout " +
                "(${SYSTEMD_STOP_TIMEOUT_MS}ms), or the host SIGKILLs the process mid-drain and the " +
                "drain merely LOOKS armed. Raising this needs a TimeoutStopSec in the unit first, " +
                "which is a change to whatever supervises the install and not this repo's.",
        )
    }
}
