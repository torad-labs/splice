// NEW: #525 (2026-11-12): the next daemon reads a conversation the retiring one held, at that conversation's turn.
package splice.provider.codex.state.retention

import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter

// why: how often a turn of a conversation the retiring daemon still holds looks again whether the hold is free. The
// retiring daemon lets go when its last turn of the conversation ends, so the wait is that turn's tail; a quarter
// second adds little to it and costs one file probe per look.
private const val HOLD_POLL_MS = 250L

/** A conversation held elsewhere at boot ([splice.provider.codex.state.handover.CodeModeHeldJournals]) is read here,
 *  once, before its first turn in this daemon touches it: the turn waits until the retiring daemon has let go (no
 *  bound of its own, as the turn lock waits; the OS frees the hold when that process ends), then the conversation is
 *  read from its journal the way the boot reads a cold one, and its turn restores it ([CodeModeRecordCache]). */
internal class CodeModeAdoption(
    private val access: CodeModeRegistryAccess,
    private val store: CodexCodeModeStore,
    private val history: CodeModeExpiredHistory,
    private val timed: CodeModeTimedSweep,
    private val waiter: Waiter = ProcessWaiter(),
) {
    suspend fun await(key: String) {
        val held = store.files.held
        if (held.isEmpty) return
        val journal = store.files.path(key)
        if (!held.holds(journal)) return
        while (!held.free(journal)) waiter.wait(HOLD_POLL_MS)
        access.withKey(key) {
            // A sibling branch of the conversation may have read it while this turn waited for the key.
            if (held.holds(journal)) {
                history.entries += store.cold.adopt(key)
                history.entries.sortBy { it.expiredAt }
                held.adopted(journal)
                timed.arm()
            }
        }
    }
}
