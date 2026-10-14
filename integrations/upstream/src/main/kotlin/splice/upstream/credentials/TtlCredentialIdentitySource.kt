// NEW: V4-160 — one account's credential evidence behind a short TTL, moved verbatim out of
// AccountSelection.kt (concentration, 2026-09-18). PoolAccount is its one owner.
package splice.upstream.credentials

import splice.core.auth.CredentialFileIdentity
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCategory
import splice.core.memory.HeapObservation
import splice.core.memory.HeapOwners
import java.util.concurrent.atomic.AtomicReference

private const val CREDENTIAL_EVIDENCE_TTL_NANOS = 2_000_000_000L
private const val CREDENTIAL_EVIDENCE_SCALAR_BYTES = 96L
private const val CREDENTIAL_IDENTITY_SCALAR_BYTES = 64L

/** Keeps policy observations off the selection monitor without treating file metadata as a generation.
 * Every sequential [refresh] reads again. Only [AccountEvidenceLoads] shares pre-read overlap. */
internal class TtlCredentialIdentitySource(
    private val delegate: AccountCredentialIdentitySource?,
) : AccountCredentialIdentitySource {
    private val publication = AtomicReference(Publication())
    private val heap = AtomicReference<HeapBudget?>(null)

    /** This wrapper belongs to one auth incarnation; only explicit invalidation changes its epoch. */
    internal val epoch: Epoch get() = publication.get().epoch

    internal fun track(budget: HeapBudget?) {
        if (budget != null) heap.compareAndSet(null, budget)
    }

    fun refresh() {
        while (true) {
            val observed = observe(epoch)
            if (observed != null && publish(observed)) return
        }
    }

    /** Revokes publication rights of reads started before a persisted reactive refresh. */
    fun invalidate() {
        while (true) {
            val current = publication.get()
            val updated = current.copy(epoch = Epoch(), cached = null)
            if (publication.compareAndSet(current, updated)) {
                current.cached?.retention?.ownerFinished()
                return
            }
        }
    }

    /** Reads outside admission locks. A suspending caller checks cancellation before publication. */
    internal fun observe(expectedEpoch: Epoch): Observation? {
        val admitted = publication.updateAndGet { current ->
            if (current.epoch === expectedEpoch) current.copy(sequence = current.sequence + 1L) else current
        }
        if (admitted.epoch !== expectedEpoch) return null
        val evidence = delegate?.credentialEvidence() ?: unknownEvidence()
        return Observation(expectedEpoch, admitted.sequence, System.nanoTime(), evidence)
    }

    /** Older completions cannot replace newer evidence, including a missing-credential tombstone. */
    internal fun publish(observed: Observation): Boolean =
        record(observed, CacheWindow.REFRESH).epoch === observed.epoch

    override fun credentialEvidence(): AccountCredentialIdentitySource.CredentialEvidence {
        while (true) {
            val current = publication.get()
            val cached = current.cached?.observation
            if (cached != null && cached.fresh) return cached.evidence
            val observed = observe(current.epoch)
            val accepted = if (observed != null) publishPolicy(observed) else null
            if (accepted != null) return accepted
        }
    }

    /** Expired policy reads participate in ordering but never renew the refresh-only TTL. */
    private fun publishPolicy(observed: Observation): AccountCredentialIdentitySource.CredentialEvidence? {
        val completed = record(observed, CacheWindow.POLICY)
        return if (completed.epoch === observed.epoch) completed.cached?.observation?.evidence else null
    }

    private fun record(observed: Observation, window: CacheWindow): Publication {
        var retention: HeapObservation? = null
        while (true) {
            val current = publication.get()
            val cachedSequence = current.cached?.observation?.sequence ?: 0L
            if (current.epoch !== observed.epoch || cachedSequence >= observed.sequence) {
                retention?.ownerFinished()
                return current
            }
            if (retention == null) retention = retained(observed.evidence)
            val accepted = when (window) {
                CacheWindow.REFRESH -> observed
                CacheWindow.POLICY -> observed.copy(readAtNanos = current.cached?.observation?.readAtNanos)
            }
            val updated = current.copy(cached = Cached(accepted, retention))
            if (publication.compareAndSet(current, updated)) {
                current.cached?.retention?.ownerFinished()
                return updated
            }
        }
    }

    /** Overlapping scalar/string estimates, not admission charges or measured process memory. */
    private fun retained(evidence: AccountCredentialIdentitySource.CredentialEvidence): HeapObservation? {
        val budget = heap.get() ?: return null
        val identity = evidence.identity
        val identityBytes = if (identity == null) {
            0L
        } else {
            CREDENTIAL_IDENTITY_SCALAR_BYTES + identity.contentDigest.length.toLong() * 2L
        }
        return HeapOwners.observe(
            evidence,
            budget,
            CREDENTIAL_EVIDENCE_SCALAR_BYTES + identityBytes,
            HeapCategory.AUTH_EVIDENCE,
        )
    }

    override fun credentialIdentity(): CredentialFileIdentity? = delegate?.credentialIdentity()

    override fun credentialPresence(): AccountCredentialIdentitySource.CredentialPresence =
        delegate?.credentialPresence()
            ?: AccountCredentialIdentitySource.CredentialPresence.UNKNOWN

    private fun unknownEvidence(): AccountCredentialIdentitySource.CredentialEvidence =
        AccountCredentialIdentitySource.CredentialEvidence(
            null,
            AccountCredentialIdentitySource.CredentialPresence.UNKNOWN,
        )

    internal class Epoch

    private enum class CacheWindow {
        REFRESH,
        POLICY,
    }

    private data class Publication(
        val epoch: Epoch = Epoch(),
        val sequence: Long = 0L,
        val cached: Cached? = null,
    )

    private data class Cached(val observation: Observation, val retention: HeapObservation?)

    internal data class Observation(
        val epoch: Epoch,
        val sequence: Long,
        val readAtNanos: Long?,
        val evidence: AccountCredentialIdentitySource.CredentialEvidence,
    ) {
        val fresh: Boolean
            get() = readAtNanos?.let { System.nanoTime() - it < CREDENTIAL_EVIDENCE_TTL_NANOS } == true
    }
}
