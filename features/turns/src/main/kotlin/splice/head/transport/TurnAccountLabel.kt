// Split out of splice/head/turn/TurnDrive.kt (2026-10-13, the concentration ratchet): the account a
// turn's perf row names. The three fields and the observation were TurnDrive's only reason to know
// about credentials and the head's name table; their values and rules are unchanged.
package splice.head.transport

import splice.head.HeadDeps

/** The account a turn's perf row names: the credential an attempt actually sent, else the login the turn
 *  chose, else the head's own label. One per drive, set by its factory and observed by its round posts. */
internal class TurnAccountLabel {
    /** Null is a state: a head whose key is gone records no account rather than a label with nothing behind it. */
    var fallback: String? = "primary"
    var names: HeadDeps.CredentialAccountNames? = null
    var observed: String? = null
        private set

    /** Refresh and failover replace this request's observation using the headers of each actual attempt, and each
     *  attempt reports its credential's digest as the newest one sent by the head and by this request's session. */
    fun observe(key: String?, sessionId: String?) {
        val known = names
        if (key != null) known?.sent(key, sessionId)
        observed = key?.let { known?.forCredential(it) }
    }

    /** The label the row carries: the observed credential's name, else [chosen], else [fallback]. */
    fun of(chosen: String?): String? = observed ?: chosen ?: fallback
}
