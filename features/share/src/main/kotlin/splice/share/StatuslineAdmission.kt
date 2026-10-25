// NEW: LAN share (docs/specs/lan-share.md). What ShareRelay asks of a statusline's body before relaying it: the
// listener pins the session the body names to the login the address chose, so the line shows that login from its
// first draw, before the session's first turn.
package splice.share

/** What a statusline's body passes before it is relayed: the session it names pinned to the address's login. */
internal fun interface StatuslineAdmission {
    /** Why [body]'s statusline may not be drawn, or null once it may. */
    fun refusal(body: ByteArray): String?
}
