// NEW: the operator's account pins as saved state (Nov 3, 2026): a Use now pin outlives the daemon.
package splice.core.auth

/** A head's pins as they are kept between daemon runs: the pin of the whole command, if any, each session's own pin,
 *  oldest first, and what each pinned login was pinned over, the limits it read at the press and still reads. A pin
 *  lets go at the first limit its login reads beyond those, so the next run weighs a pin it puts back against them,
 *  never against whatever its login reads by then (PR #468 review: a limit read just before a restart kept the pin).
 *  A login [over] does not name (a file from before it) is weighed against its first read. Labels are the pool's own
 *  account identities. */
public data class SavedPins(
    val head: String? = null,
    val sessions: Map<String, String> = emptyMap(),
    val over: Map<String, Set<String>> = emptyMap(),
)
