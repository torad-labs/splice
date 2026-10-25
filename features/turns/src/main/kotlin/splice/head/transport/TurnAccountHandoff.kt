// NEW: a native refusal may move an uncommitted turn to a free, never-visited login.
package splice.head.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splice.head.admission.TurnQuota
import splice.head.turn.TurnDrive
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountSelection
import splice.upstream.credentials.Selection

/** Acceptance and client commitment close the same gate that changes account and quota together. */
internal class TurnAccountHandoff(
    private val pool: AccountPool,
    private val quotas: TurnQuota,
) {
    private val refused = linkedSetOf<String>()
    private var committed = false

    @Synchronized
    fun commit() {
        committed = true
    }

    /** Evidence loading suspends outside the commitment monitor. Selection still checks current
     *  eligibility and acquires a caller-owned probe; a raced commitment never adopts that probe. */
    suspend fun moveAwaitingEvidence(drive: TurnDrive): Boolean {
        val pending = pending(drive) ?: return false
        val selected = pool.awaitingEvidence.select(drive.meta.scope.sessionId, pending.excluded)
        val next = when (selected) {
            is Selection.Chosen -> selected.account
            is Selection.Exhausted -> return false
        }
        var adopted = false
        try {
            currentCoroutineContext().ensureActive()
            // LAN share: a node's word cannot move onto a login that forwards it; the vendor would see the word.
            adopted = !CallerCredential.unheldNodeWord(drive.turnHeaders, next.account.auth) &&
                adopt(drive, pending.previous, next)
            return adopted
        } finally {
            if (!adopted) next.releaseCredentialProbe()
        }
    }

    private data class PendingHandoff(val previous: AccountSelection, val excluded: Set<String>)

    @Synchronized
    private fun pending(drive: TurnDrive): PendingHandoff? {
        if (committed) return null
        val previous = drive.account ?: return null
        refused += previous.account.label
        return PendingHandoff(previous, refused.toSet())
    }

    @Synchronized
    private fun adopt(drive: TurnDrive, previous: AccountSelection, next: AccountSelection): Boolean {
        if (committed || drive.account !== previous) return false
        if (pool.members.none { it === next.account }) return false
        val quota = quotas.forSession(drive.meta.scope.sessionId, next)
        previous.releaseCredentialProbe()
        drive.account = next
        drive.quota = quota
        return true
    }
}
