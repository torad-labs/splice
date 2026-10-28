// NEW: a native refusal may move an uncommitted turn to a free, never-visited login.
package splice.head.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.head.admission.TurnQuota
import splice.head.turn.TurnDrive
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountSelection
import splice.upstream.credentials.Selection
import java.time.Instant

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
        val available = pool.awaitingEvidence.select(drive.meta.scope.sessionId, pending.excluded)
        val selected = if (available is Selection.Exhausted && earlierHeld(drive)) {
            pool.awaitingEvidence.select(drive.meta.scope.sessionId)
        } else {
            available
        }
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

    /** A free, untried login takes the turn first; when every login is held, an earlier reset may take it. The just-read
     *  [cause] supplies the current reset before the retry executor publishes that login's hold. */
    fun otherFree(drive: TurnDrive, cause: FailureCause): Boolean {
        if (synchronized(this) { committed }) return false
        val current = drive.account?.account?.label
        val tried = synchronized(this) { refused.toSet() }
        val reset = when (cause) {
            is FailureCause.Provider.Limited -> (cause.lift as? Lift.At)?.at
            is FailureCause.Provider.NeedsPayment -> (cause.lift as? Lift.At)?.at
            is FailureCause.Provider, is FailureCause.Path, is FailureCause.Splice -> null
        }
        return pool.view(drive.meta.scope.sessionId).accounts
            .any { it.available && it.label != current && it.label !in tried } || earlierHeld(drive, reset)
    }

    /** Once every login is held, move the uncommitted turn to the one whose reset is earlier, even if it was tried
     *  already. A strict earlier comparison prevents bouncing between logins held to the same reset. */
    private fun earlierHeld(drive: TurnDrive, reset: Instant? = null): Boolean {
        val current = drive.account?.account?.label ?: return false
        val session = drive.meta.scope.sessionId
        val view = pool.view(session)
        val resets = view.blockedUntilEpochSecondsByLabel
        val currentReset = reset?.epochSecond ?: resets[current] ?: return false
        return view.accounts.any { account ->
            account.label != current && account.credential.present && account.credential.exclusionReason == null &&
                resets[account.label]?.let { it < currentReset } == true
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
