// NEW: the operator's account pins as saved state (Nov 3, 2026): a Use now pin outlives the daemon.
package splice.core.auth

/** A head's pins as they are kept between daemon runs: the pin of the whole command, if any, and each session's own
 *  pin, oldest first. Labels are the pool's own account identities. */
public data class SavedPins(val head: String? = null, val sessions: Map<String, String> = emptyMap())
