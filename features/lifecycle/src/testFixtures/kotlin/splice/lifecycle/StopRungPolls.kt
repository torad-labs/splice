// NEW: the CLI half of the stop ladder (V4-74), read from its declarations for app's
// DaemonStopBudgetTest, which orders each rung against the daemon's halt floor. Before LAYOUT-01 that
// test multiplied these counts directly; the rungs moved to :features-lifecycle, where they stay
// internal, and a fixture is the cross-module reader a test may use (PublicSurfaceLawTest counts no
// test as a consumer, so widening the constants for a sibling's test is the shape it refuses).
package splice.lifecycle

import splice.lifecycle.restart.GRACEFUL_POLLS
import splice.lifecycle.restart.SIGTERM_POLLS
import splice.lifecycle.start.STARTUP_POLLS

/** The three CLI rungs as poll counts, each the constant its verb polls against. */
public object StopRungPolls {
    /** DaemonStop's graceful rung: the daemon's halt floor must sit inside it. */
    public const val GRACEFUL: Int = GRACEFUL_POLLS

    /** DaemonStop's SIGTERM rung: it must wait past the halt floor the SIGTERM hook guarantees. */
    public const val SIGTERM: Int = SIGTERM_POLLS

    /** DaemonLaunch's startup budget: the spawner must outlast the new daemon's lock wait. */
    public const val STARTUP: Int = STARTUP_POLLS
}
