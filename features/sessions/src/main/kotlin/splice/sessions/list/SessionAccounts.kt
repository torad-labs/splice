// NEW: v0.4.0 spec section 9 — `splice sessions` names the account each session is on and the pin it is under.
package splice.sessions.list

import splice.core.util.EnvReader

/** One session's account as the daemon reports it: the login it is on and the account it is pinned to, if any. */
public data class SessionAccountLine(val account: String?, val pin: String?)

/** Reads the running daemon's per-session accounts by session id. Empty when the daemon cannot be asked,
 *  so the listing still prints from the registry alone. */
public fun interface SessionAccounts {
    public fun read(envReader: EnvReader): Map<String, SessionAccountLine>
}
