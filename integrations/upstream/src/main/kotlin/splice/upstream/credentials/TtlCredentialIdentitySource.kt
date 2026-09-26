// NEW: V4-160 — one account's credential evidence behind a short TTL, moved verbatim out of
// AccountSelection.kt (concentration, 2026-09-18). PoolAccount is its one owner.
package splice.upstream.credentials

import splice.core.auth.CredentialFileIdentity
import java.util.concurrent.atomic.AtomicReference

// why: 2s keeps the sticky-session monitor off the credential file for a whole select loop, then
// re-reads so a freshly-written auth.json is seen
private const val CREDENTIAL_EVIDENCE_TTL_NANOS = 2_000_000_000L

/** Caches one account's credential evidence behind a short TTL so the sticky-session monitor never
 *  holds a filesystem round-trip. [refresh] re-reads the delegate OFF the monitor; within the TTL
 *  [credentialEvidence] returns the cached observation instead of touching the credential file. */
internal class TtlCredentialIdentitySource(
    private val delegate: AccountCredentialIdentitySource?,
) : AccountCredentialIdentitySource {
    private val cached = AtomicReference<Cached?>(null)

    fun refresh() {
        val evidence = delegate?.credentialEvidence() ?: unknownEvidence()
        cached.set(Cached(System.nanoTime(), evidence))
    }

    override fun credentialEvidence(): AccountCredentialIdentitySource.CredentialEvidence {
        val current = cached.get()
        if (current != null && System.nanoTime() - current.readAtNanos < CREDENTIAL_EVIDENCE_TTL_NANOS) {
            return current.evidence
        }
        return delegate?.credentialEvidence() ?: unknownEvidence()
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

    private data class Cached(
        val readAtNanos: Long,
        val evidence: AccountCredentialIdentitySource.CredentialEvidence,
    )
}
