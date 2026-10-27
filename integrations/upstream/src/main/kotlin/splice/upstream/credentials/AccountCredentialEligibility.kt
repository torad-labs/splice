// NEW: V4-10 timed authentication holds and one-owner recovery probes per pooled account.
package splice.upstream.credentials

import splice.core.auth.CredentialFileIdentity
import splice.core.failure.AccountBlock
import splice.core.util.WallClock
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
) {
    private val evidence = CredentialEvidence(identitySource, configuredCredentialPresent)
    private val state = AtomicReference(evidence.initialState())

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
            block = current.hold?.block.takeIf { holdActive },
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

    private fun scheduleFailure(lease: Lease, block: AccountBlock? = null) {
        while (true) {
            val current = reconcile()
            if (current.generation != lease.generation) return
            val failureCount = nextFailureCount(current, lease)
            val updated = current.copy(
                failureCount = failureCount,
                hold = Hold(lease.now() + holdMs(failureCount), block),
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
            val updated = evidence.observe().reconciled(current)
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
        /** While held: the provider refused the account itself until its owner takes this step, which no sign-in
         *  changes. Null for every other hold. */
        val block: AccountBlock? = null,
    )

    private class CredentialEvidence(
        private val source: AccountCredentialIdentitySource?,
        private val configuredCredentialPresent: Boolean,
    ) {
        val tracksIdentity: Boolean get() = source != null

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

        fun reconciled(current: State): State = when {
            identity != null -> current.observeIdentity(identity)
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
        fun observeIdentity(observed: CredentialFileIdentity): State = when {
            !credentialPresent -> recovered(observed)
            identity == null -> copy(identity = observed)
            identity != observed -> recovered(observed)
            else -> this
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
    }

    /** A hold on this login after its provider refused it, until [untilEpochMillis]. With [block], the provider refused
     *  the account itself until its owner takes that step, which only a turn served on the login again shows. */
    private data class Hold(val untilEpochMillis: Long, val block: AccountBlock? = null)
}
