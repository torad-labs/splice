// NEW: a native refusal may move an uncommitted turn to a free, never-visited login.
//
// A REFUSED LOGIN COMES BACK BY NO RESET (Nov 17, 2026). A login whose token could not be renewed has no reset, so the
// move to an earlier-held login, which compares resets, never found one earlier than "none": turns ended "login expired"
// while five other logins of the head were signed in, each held for the turn's model. A login that is signed out,
// excluded or blocked now counts as never coming back, so every held login is earlier than it.
//
// AND A MODEL WITH ROOM TAKES THE TURN (Nov 17, 2026): [otherModel] says whether the turn's re-send goes on under another
// model ([HeldModelFallback]) while no login can serve the one it asks for.
package splice.head.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LoginOwner
import splice.head.admission.TurnQuota
import splice.head.turn.TurnDrive
import splice.head.turn.fallback.HeldModelFallback
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountPoolView
import splice.upstream.credentials.AccountSelection
import splice.upstream.credentials.AccountView
import splice.upstream.credentials.Selection
import java.time.Instant

/** Acceptance and client commitment close the same gate that changes account and quota together. */
internal class TurnAccountHandoff(
    private val pool: AccountPool,
    private val quotas: TurnQuota,
    private val fallback: HeldModelFallback? = null,
) {
    /** Each login that refused this turn, with the pool's join count when it did ([AccountPool.joins], PoolJoins.count). */
    private val refused = linkedMapOf<String, Long>()
    private var committed = false

    @Synchronized
    fun commit() {
        committed = true
    }

    /** Evidence loading suspends outside the commitment monitor. Selection still checks current
     *  eligibility and acquires a caller-owned probe; a raced commitment never adopts that probe. */
    suspend fun moveAwaitingEvidence(drive: TurnDrive): Boolean {
        val pending = pending(drive) ?: return false
        val model = drive.meta.route.upstreamModel
        val session = drive.meta.scope.sessionId
        // own = false: the turn was counted toward the command's usual model at its admission. Counted again here, a
        // login running out would hand that say to the helpers' turns it refused, at the moment the screens are read.
        val available = pool.awaitingEvidence.select(session, pending.excluded, model = model, own = false)
        val selected = if (available is Selection.Exhausted && earlierHeld(drive)) {
            pool.awaitingEvidence.select(session, model = model, own = false)
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
        // A login that left the pool since, or whose label is another account's now, is not this turn's login any more.
        val current = drive.account?.account?.takeIf { held -> pool.members.any { it === held } }?.label
        val tried = synchronized(this) { tried() }
        val reset = when (cause) {
            is FailureCause.Provider.Limited -> (cause.lift as? Lift.At)?.at
            is FailureCause.Provider.NeedsPayment -> (cause.lift as? Lift.At)?.at
            is FailureCause.Provider, is FailureCause.Path, is FailureCause.Splice -> null
        }
        // Each login as a turn of this model meets it: a login whose provider refused another model for the week is
        // free for this one (Nov 3, 2026).
        return pool.view(drive.meta.scope.sessionId, drive.meta.route.upstreamModel).accounts
            .any { it.available && it.label != current && it.label !in tried } || earlierHeld(drive, reset, gone(cause))
    }

    /** Whether the uncommitted turn's re-send goes on under another model, one some login serves now, while no login
     *  serves the one it asks for. The just-read [cause] holds the current login for that model until its reset before
     *  the retry executor publishes the hold. */
    fun otherModel(drive: TurnDrive, cause: FailureCause): Boolean {
        if (synchronized(this) { committed }) return false
        val fallback = fallback ?: return false
        val current = drive.account?.account?.label
        val reset = ((cause as? FailureCause.Provider.Limited)?.lift as? Lift.At)?.at?.epochSecond
        val heldToo = if (current != null && reset != null) mapOf(current to reset) else emptyMap()
        return fallback.instead(drive.meta.route.originalModel, drive.meta.scope.sessionId, heldToo) != null
    }

    /** A refusal of the login itself: no reset brings it back, only a sign-in or its owner's act. */
    private fun gone(cause: FailureCause): Boolean = when (cause) {
        is FailureCause.Provider.AuthRejected -> cause.owner == LoginOwner.SPLICE
        is FailureCause.Provider.Forbidden -> cause.block != null
        is FailureCause.Provider, is FailureCause.Path, is FailureCause.Splice -> false
    }

    /** Once every login is held, move the uncommitted turn to the one whose reset is earlier, even if it was tried
     *  already. A strict earlier comparison prevents bouncing between logins held to the same reset. A current login
     *  that was refused ([refused]) or is signed out now resets never, so any held login is earlier. */
    private fun earlierHeld(drive: TurnDrive, reset: Instant? = null, refused: Boolean = false): Boolean {
        val current = drive.account?.account?.label ?: return false
        val view = pool.view(drive.meta.scope.sessionId, drive.meta.route.upstreamModel)
        val resets = view.blockedUntilEpochSecondsByLabel
        val currentReset = currentReset(view, current, reset, refused) ?: return false
        return view.accounts.any { account ->
            account.label != current && signedIn(account) && (resets[account.label] ?: Long.MAX_VALUE) < currentReset
        }
    }

    /** When [current] serves again, in epoch seconds: never where it was [refused] or is signed out, else at the
     *  [reset] just read or the one the pool holds; null where nothing holds it. */
    private fun currentReset(view: AccountPoolView, current: String, reset: Instant?, refused: Boolean): Long? {
        val out = refused || view.accounts.any { it.label == current && !signedIn(it) }
        return if (out) Long.MAX_VALUE else reset?.epochSecond ?: view.blockedUntilEpochSecondsByLabel[current]
    }

    private fun signedIn(login: AccountView): Boolean =
        login.credential.let { it.present && it.exclusionReason == null && it.block == null }

    private data class PendingHandoff(val previous: AccountSelection, val excluded: Set<String>)

    @Synchronized
    private fun pending(drive: TurnDrive): PendingHandoff? {
        if (committed) return null
        val previous = drive.account ?: return null
        // Only a login still in the pool refuses under its label: one replaced under the same label is another account.
        if (pool.members.any { it === previous.account }) refused[previous.account.label] = pool.joins.count
        return PendingHandoff(previous, tried())
    }

    /** The logins this turn does not go back to: the ones that refused it since the newest join. A join opens every
     *  login again, since what refused before may be another account now or signed in again, and a login still spent
     *  is kept out by the hold its own refusal left on it. Called under this monitor. */
    private fun tried(): Set<String> = pool.joins.count.let { now -> refused.filterValues { it == now }.keys.toSet() }

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
