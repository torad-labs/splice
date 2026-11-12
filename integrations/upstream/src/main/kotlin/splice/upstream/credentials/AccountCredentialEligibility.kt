// NEW: V4-10 timed authentication holds and one-owner recovery probes per pooled account.
package splice.upstream.credentials

import splice.core.auth.CredentialFileIdentity
import splice.core.failure.AccountBlock
import splice.core.util.WallClock
import splice.upstream.retry.LoginHoldStore
import splice.upstream.retry.SavedLoginHold
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TERMINAL_401 = "terminal_401"
private const val CREDENTIAL_MISSING = "credential_missing"
private const val RECOVERY_PROBE_IN_FLIGHT = "recovery_probe_in_flight"
private const val FIRST_AUTH_HOLD_MS = 300_000L
private const val MAX_AUTH_HOLD_MS = 3_600_000L
private const val MAX_AUTH_FAILURE_COUNT = 5
private val nextProbeToken = AtomicLong()

internal class AccountCredentialEligibility(
    identitySource: AccountCredentialIdentitySource?,
    configuredCredentialPresent: Boolean,
    kept: LoginHoldStore? = null,
) {
    private val evidence = CredentialEvidence(identitySource, configuredCredentialPresent)
    private val state = KeptState(evidence, kept)

    /** A lease to send on this login, or null while it may not: no credential, a hold, or a probe already in flight.
     *  A login its provider blocked until its owner acts ([AccountBlock]) is held from the turns another login can
     *  take, which never ask it ([status] names it unselectable), yet a turn left with no other login may ask it
     *  through its hold: the provider's answer is the only way splice learns that the owner acted. */
    fun acquire(at: Long, now: WallClock): Lease? {
        while (true) {
            val current = reconcile()
            // A hold with no block shuts the login out until it lapses; a block lets a turn with no other login ask.
            val shutOut = current.hold?.takeIf { it.untilEpochMillis > at && it.block == null } != null
            val excluded = !current.credentialPresent || shutOut
            if (excluded || current.probeToken != null) return null
            val recoveryProbe = current.hold != null
            if (!recoveryProbe) return Lease(current.identity, current.generation, null, now)
            val token = nextProbeToken.incrementAndGet()
            val updated = current.copy(probeToken = token, generation = current.generation + 1L)
            if (state.compareAndSet(current, updated)) {
                return Lease(updated.identity, updated.generation, token, now)
            }
        }
    }

    fun status(at: Long): Status {
        val current = reconcile()
        val holdActive = current.hold?.let { it.untilEpochMillis > at } == true
        val reason = when {
            !current.credentialPresent -> CREDENTIAL_MISSING
            holdActive -> TERMINAL_401
            current.probeToken != null -> RECOVERY_PROBE_IN_FLIGHT
            else -> null
        }
        return Status(
            credentialPresent = current.credentialPresent,
            selectable = current.credentialPresent && !holdActive && current.probeToken == null,
            excludedUntilEpochMillis = current.hold?.untilEpochMillis.takeIf { holdActive },
            reason = reason,
            renewable = !current.credentialPresent && current.renewable,
            // The block stands past the hold's time: only a served turn or another account's credential ends it.
            block = current.hold?.block,
        )
    }

    /** The provider refused this login: held from later turns, and with [block], until its owner acts. */
    fun reject(lease: Lease, block: AccountBlock? = null) {
        scheduleFailure(lease, block)
    }

    fun missing(lease: Lease) {
        if (evidence.tracksIdentity) scheduleFailure(lease) else markLegacyMissing(lease)
    }

    fun release(lease: Lease) {
        val token = lease.probeToken ?: return
        while (true) {
            val current = state.get()
            if (current.probeToken != token) return
            val updated = current.copy(probeToken = null, generation = current.generation + 1L)
            if (state.compareAndSet(current, updated)) return
        }
    }

    fun succeeded(lease: Lease): Lease {
        while (true) {
            val current = reconcile()
            if (current.generation != lease.generation) return lease
            val updated = current.resetRuntime()
            if (state.compareAndSet(current, updated)) {
                return Lease(updated.identity, updated.generation, null, lease.now)
            }
        }
    }

    fun refreshSucceeded(lease: Lease): Lease {
        while (true) {
            val current = state.get()
            if (current.generation != lease.generation) return lease
            val updated = evidence.observe().refreshed(current)
            if (state.compareAndSet(current, updated)) {
                return if (updated.credentialPresent) {
                    Lease(updated.identity, updated.generation, null, lease.now)
                } else {
                    lease
                }
            }
        }
    }

    fun reset() {
        while (true) {
            val current = reconcile()
            if (state.compareAndSet(current, current.resetRuntime())) return
        }
    }

    /** The person asks for this login again ([AccountPool.askAgain]). Its hold ends [at] this moment and its series
     *  of refusals starts over, so the next turn that reaches it asks the provider, and a refusal holds it for the
     *  first, shortest time. A block its provider put on it stays named: splice learns that the owner acted only from
     *  the provider's next answer, so the login reads blocked until a turn is served on it ([succeeded]). On Nov 11,
     *  2026 this let go of the block as well, and a login pressed Try again read as one a turn could take for three
     *  minutes, until Anthropic refused it again. */
    fun askAgain(at: Long) {
        while (true) {
            val current = reconcile()
            val named = current.hold?.takeIf { it.block != null }?.copy(untilEpochMillis = at)
            if (state.compareAndSet(current, current.resetRuntime().copy(hold = named))) return
        }
    }

    private fun scheduleFailure(lease: Lease, block: AccountBlock? = null) {
        while (true) {
            val current = reconcile()
            if (current.generation != lease.generation) return
            val failureCount = nextFailureCount(current, lease)
            val updated = current.copy(
                failureCount = failureCount,
                hold = Hold(lease.now() + holdMs(failureCount), block, block?.let { evidence.account() }),
                probeToken = null,
                generation = current.generation + 1L,
            )
            if (state.compareAndSet(current, updated)) return
        }
    }

    private fun markLegacyMissing(lease: Lease) {
        while (true) {
            val current = state.get()
            if (current.generation != lease.generation) return
            if (state.compareAndSet(current, current.observeMissing())) return
        }
    }

    private fun reconcile(): State {
        while (true) {
            val current = state.get()
            val updated = evidence.observe().reconciled(current, evidence)
            if (updated == current) return current
            if (state.compareAndSet(current, updated)) return updated
        }
    }

    private fun nextFailureCount(current: State, lease: Lease): Int = when {
        lease.probeToken != null -> minOf(current.failureCount + 1, MAX_AUTH_FAILURE_COUNT)
        current.failureCount == 0 -> 1
        else -> current.failureCount
    }

    private fun holdMs(failureCount: Int): Long {
        val exponent = (failureCount - 1).coerceIn(0, MAX_AUTH_FAILURE_COUNT - 1)
        return minOf(FIRST_AUTH_HOLD_MS shl exponent, MAX_AUTH_HOLD_MS)
    }

    data class Lease(
        val identity: CredentialFileIdentity?,
        val generation: Long,
        val probeToken: Long?,
        val now: WallClock,
    )

    data class Status(
        val credentialPresent: Boolean,
        val selectable: Boolean,
        val excludedUntilEpochMillis: Long?,
        val reason: String?,
        /** Not present, and its own command renews it (CredentialEvidence.renewable): no sign-in is needed. */
        val renewable: Boolean = false,
        /** The provider refused the account itself until its owner takes this step, which no sign-in changes. It
         *  stands from that refusal until the login serves a turn or its credential names another account: through
         *  the hold, and after the hold's time has run out and the next turn may ask again. Null for every other
         *  hold. Until Nov 11, 2026 it ended with the hold's time: a login refused at 2:29:35 AM CT read "Use now" on
         *  its card from 3:29 AM, and a session that found its recovery probe running at 2:29:35 AM moved off it
         *  with only "account unavailable" for a reason. */
        val block: AccountBlock? = null,
    )

    /** The login's state. Its hold is written to [kept] at each change and put back from it at the start, so a
     *  restart does not send the next turns to a login its provider has just refused (Nov 12, 2026). */
    private class KeptState(evidence: CredentialEvidence, private val kept: LoginHoldStore?) {
        private val now = AtomicReference(evidence.initialState().restored(kept, evidence))

        fun get(): State = now.get()

        fun compareAndSet(current: State, updated: State): Boolean {
            if (!now.compareAndSet(current, updated)) return false
            if (updated.hold != current.hold || updated.failureCount != current.failureCount) keep()
            return true
        }

        /** Writes the hold as it stands at this moment, so of two changes in a row the later one is the one kept. */
        @Synchronized
        private fun keep() {
            kept?.save(now.get().saved())
        }
    }

    private class CredentialEvidence(
        private val source: AccountCredentialIdentitySource?,
        private val configuredCredentialPresent: Boolean,
    ) {
        val tracksIdentity: Boolean get() = source != null

        /** The provider account the credential names now, where its source proves one (credentialAccount). */
        fun account(): String? = source?.credentialAccount()

        fun initialState(): State {
            val observed = observe()
            val present = observed.initialPresence(configuredCredentialPresent)
            return State(observed.identity, present, 0, null, null, 0L, renewable = !present && observed.renewable)
        }

        fun observe(): Observation {
            val observed = source?.credentialEvidence()
            return Observation(
                observed?.identity,
                observed?.presence ?: AccountCredentialIdentitySource.CredentialPresence.UNKNOWN,
                observed?.renewable == true,
            )
        }
    }

    private data class Observation(
        val identity: CredentialFileIdentity?,
        val presence: AccountCredentialIdentitySource.CredentialPresence,
        val renewable: Boolean,
    ) {
        fun initialPresence(configured: Boolean): Boolean = when {
            identity != null -> true
            presence == AccountCredentialIdentitySource.CredentialPresence.PRESENT -> true
            presence == AccountCredentialIdentitySource.CredentialPresence.MISSING -> false
            else -> configured
        }

        fun reconciled(current: State, source: CredentialEvidence): State = when {
            identity != null -> current.observeIdentity(identity, source)
            presence == AccountCredentialIdentitySource.CredentialPresence.PRESENT -> current.observePresent()
            presence == AccountCredentialIdentitySource.CredentialPresence.MISSING ->
                current.observeMissing().copy(renewable = renewable)
            else -> current
        }

        fun refreshed(current: State): State = when (presence) {
            AccountCredentialIdentitySource.CredentialPresence.MISSING ->
                current.observeMissing().copy(renewable = renewable)
            AccountCredentialIdentitySource.CredentialPresence.PRESENT,
            AccountCredentialIdentitySource.CredentialPresence.UNKNOWN,
            -> current.recovered(identity)
        }
    }

    private data class State(
        val identity: CredentialFileIdentity?,
        val credentialPresent: Boolean,
        val failureCount: Int,
        val hold: Hold?,
        val probeToken: Long?,
        val generation: Long,
        val renewable: Boolean = false,
    ) {
        /** A changed credential file is a new credential, which ends a hold, except a hold on the ACCOUNT: the
         *  provider blocked the account, and a rewritten file that still names it is the same blocked login. At
         *  12:17:14 AM CT on Nov 11, 2026, Claude Code rewrote its credential file around the same token one second
         *  after its login was held for its organization's block; the hold ended with the rewrite, and the next
         *  session's first request went to that login 56 seconds after the hold began. [source] is asked for
         *  its account only then. A file that names another account, or none splice can prove, ends the hold. */
        fun observeIdentity(observed: CredentialFileIdentity, source: CredentialEvidence): State = when {
            !credentialPresent -> recovered(observed)
            identity == null -> copy(identity = observed)
            identity == observed -> this
            hold?.blocks(source) == true -> copy(identity = observed)
            else -> recovered(observed)
        }

        fun observePresent(): State = if (credentialPresent) this else recovered(null)

        fun observeMissing(): State = if (!credentialPresent) {
            this
        } else {
            copy(
                identity = null,
                credentialPresent = false,
                failureCount = 0,
                hold = null,
                probeToken = null,
                generation = generation + 1L,
            )
        }

        fun recovered(observed: CredentialFileIdentity?): State = copy(
            identity = observed ?: identity,
            credentialPresent = true,
            renewable = false,
            failureCount = 0,
            hold = null,
            probeToken = null,
            generation = generation + 1L,
        )

        fun resetRuntime(): State = copy(
            failureCount = 0,
            hold = null,
            probeToken = null,
            generation = generation + 1L,
        )

        /** This login's hold as a restart keeps it; null when nothing holds the login. */
        fun saved(): SavedLoginHold? =
            hold?.let { SavedLoginHold(it.untilEpochMillis, failureCount, it.block, it.account, identity) }

        /** The state a start begins from. What [kept] holds stands again on the credential it was put on: the same
         *  account where the provider blocked the account, the same file otherwise. On any other credential, or on
         *  none, it is dropped: a sign-in while the daemon was down is a new credential, which ends a hold. */
        fun restored(kept: LoginHoldStore?, source: CredentialEvidence): State {
            val saved = kept?.load() ?: return this
            val hold = Hold(saved.untilEpochMillis, saved.block, saved.account)
            val sameFile = saved.identity != null && saved.identity == identity
            val stands = credentialPresent && if (saved.block != null) hold.blocks(source) else sameFile
            if (stands) return copy(failureCount = saved.failureCount, hold = hold)
            kept?.save(null)
            return this
        }
    }

    /** A hold on this login after its provider refused it, until [untilEpochMillis]. With [block], the provider refused
     *  the account itself until its owner takes that step, which only a turn served on the login again shows;
     *  [account] is the account the credential named when it was refused, where its source proves one. */
    private data class Hold(val untilEpochMillis: Long, val block: AccountBlock? = null, val account: String? = null) {
        /** Whether this hold is a block on the very account the credential in [source] names now. */
        fun blocks(source: CredentialEvidence): Boolean =
            block != null && account != null && account == source.account()
    }
}
