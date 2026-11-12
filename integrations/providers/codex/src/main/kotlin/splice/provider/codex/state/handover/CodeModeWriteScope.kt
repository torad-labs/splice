// NEW: #525 (2026-11-12): which conversations a retiring daemon may still write.
package splice.provider.codex.state.handover

import java.util.concurrent.ConcurrentHashMap

/** Until the head retires, every conversation is this daemon's to write. From the retire on, only the
 *  conversations it still holds are: the store writes, renames and deletes nothing of any other one, whatever path
 *  asks (a late cell, a sweep, the final save), because the next daemon owns it now. */
internal class CodeModeWriteScope {
    @Volatile private var retired = false
    private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun allows(key: String): Boolean = !retired || key in held

    /** The head retires holding [keys]; every other conversation is let go. */
    fun retire(keys: Set<String>) {
        held += keys
        retired = true
    }

    /** [key] is let go too: its last write is done. */
    fun release(key: String) {
        held -= key
    }
}
