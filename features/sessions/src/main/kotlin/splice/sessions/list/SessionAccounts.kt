// NEW: v0.4.0 spec section 9 — `splice sessions` names the account each session is on and the pin it is under.
package splice.sessions.list

import splice.core.util.EnvReader

/** One session's account as the daemon reports it: the login it is on and the account it is pinned to, if any. */
public data class SessionAccountLine(val account: String?, val pin: String?)

/** A session is named by its head AND its id: two heads can register the same id with different pins, and a join
 *  by id alone gave both rows whichever registration came last (review of adf35c39e, finding 2). */
public data class SessionAccountKey(val head: String?, val sessionId: String)

/** Builds [SessionAccountKey]s the one way /api/sessions and the listing both spell a head. */
public object SessionAccountKeys {
    /** The head label a session splice cannot place carries in both places. */
    private const val UNPLACED = "unknown head"

    public fun of(head: String?, sessionId: String): SessionAccountKey =
        SessionAccountKey(head?.takeIf { it.isNotBlank() && it != UNPLACED }, sessionId)
}

/** Reads the running daemon's per-session accounts. Empty when the daemon cannot be asked, so the listing
 *  still prints from the registry alone. */
public fun interface SessionAccounts {
    public fun read(envReader: EnvReader): Map<SessionAccountKey, SessionAccountLine>
}
