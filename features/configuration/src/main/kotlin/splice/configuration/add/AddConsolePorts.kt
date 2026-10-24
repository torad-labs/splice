// NEW: V4-220 item 3 (2026-09-25) — what the console's add reaches outside this module through: the
// sign-in for a head the topology does not hold yet, and the daemon's own restart. app composes both
// (ConsoleWiring, AddMount): the login flows live there, and the restart is features/lifecycle's, which
// this module does not see.
package splice.configuration.add

import splice.accounts.signin.LoginStatus
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology

/** The console's login seam (V4-132's LoginSessions) started for a CANDIDATE: its provider and the
 *  topology it would join, since neither is in splice.toml until the save. Nothing restarts when the
 *  credential lands; the save's restart is what brings the head up. */
public interface AddSignIn {
    /** Starts the flow off-request and answers with its first view. */
    public fun start(key: String, provider: ProviderConfig, topology: Topology): LoginStatus

    /** What the flow started by [start] has announced so far, or null for an id it never issued. */
    public fun poll(id: String): LoginStatus?
}

/** A compaction the save's restart waits for: the head holding it and how long it has run. */
public data class AddWaitingCompaction(val head: String, val ageMs: Long)

/** Where the restart after a save stands, as the daemon took it on. */
public sealed class AddRestartTaken {
    /** Taken on, waiting for [compactions] to finish; the daemon drains itself afterwards. */
    public data class Waiting(val compactions: List<AddWaitingCompaction>) : AddRestartTaken()

    /** Taken on with nothing to wait for: the caller requests the drain once its answer is written. */
    public data object Draining : AddRestartTaken()

    /** Not taken on (nothing would bring the daemon back, or the daemon cannot tell); the head is saved
     *  and comes up at the next restart. */
    public data class Refused(val reason: String) : AddRestartTaken()
}

/** The restart a save takes on: the SAME decision and compaction wait as the console's restart button
 *  (features/lifecycle's DaemonRestarts), never a second path around it. */
public interface AddDaemonRestart {
    /** Takes the restart on. Never drains itself: [AddRestartTaken.Draining] asks the caller to. */
    public fun take(): AddRestartTaken

    /** Requests the drain a [AddRestartTaken.Draining] answer promised. */
    public fun drain()
}

/** Where a route reads the console's [AddConsole] AT CALL TIME: the daemon assigns it after the routes
 *  are constructed, and a route that captured it would answer unwired forever. */
public fun interface AddConsoleSource {
    public operator fun invoke(): AddConsole?
}
