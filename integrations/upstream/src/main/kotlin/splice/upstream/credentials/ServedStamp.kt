// NEW: when a login last served a session, stamped by a turn that ended cleanly on it and by nothing else.
package splice.upstream.credentials

import splice.core.util.WallClock
import java.util.concurrent.atomic.AtomicReference

/** When a session was last served on its login, which says whether its prompt cache may still be warm there. A turn
 *  stamps it when it ends cleanly ([AccountSelection.markTurnSucceeded]). Selecting, being refused at admission, being
 *  cancelled or failing before any byte never does, so none of them can renew the hour. The session's record and every
 *  selection made from it share one, so a stamp lands even after a later selection replaced the record. */
internal class ServedStamp(private val now: WallClock) {
    private val servedAt = AtomicReference<Long?>(null)

    /** The wall time of the last clean serve, or null when this login has not served the session yet. */
    val at: Long? get() = servedAt.get()

    fun stamp() {
        servedAt.set(now())
    }
}
