// NEW: LAN share, round 16 of PR #419's review. What a machine's look (RemoteSessions) said of it last, so each change
// is said in the log once, and whether it holds this machine's sessions. Lifted out of the look's loop when its read
// and its offer came apart, which gave the loop a fourth thing to remember.
package splice.share.remote

/** Whether the machine is [failing] to answer, [refused] this machine's offer, or has its Sessions switch [off] here,
 *  as the log last said; and whether it was [offered] this machine's sessions, which it holds until told otherwise. */
internal class RemoteLookState {
    var failing: Boolean = false
    var refused: Boolean = false
    var off: Boolean = false
    var offered: Boolean = false

    /** The switch went off: the machine holds none of this one's sessions now, and each change is said anew once it is
     *  on again. */
    fun switchedOff() {
        off = true
        offered = false
        failing = false
        refused = false
    }
}
