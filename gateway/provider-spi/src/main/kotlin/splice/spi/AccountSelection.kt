// NEW: v0.4.0 FEATURES.md §11 — one account chosen once at the turn boundary.
package splice.spi

import splice.core.auth.CredentialFileIdentity
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.QuotaSnapshot
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** One shared syntax gate for labels at persistence and selection boundaries. */
public object AccountLabelPolicy {
    private val safe = Regex("[a-z0-9][a-z0-9._-]{0,47}")

    public fun isSafe(label: String): Boolean = safe.matches(label)
}

/** Reads one account's latest provider quota without coupling the SPI to gateway persistence. */
public fun interface AccountQuotaSource {
    public fun snapshot(): QuotaSnapshot?
}

/** Epoch time seam used to compare provider reset timestamps without a process-global clock in policy code. */
public fun interface AccountNow {
    public operator fun invoke(): Long
}

/** One OAuth identity in a head-local pool. Secrets remain behind [auth]. */
public data class PoolAccount(
    public val label: String,
    public val primary: Boolean,
    public val auth: RefreshableAuthProvider,
    public val quota: AccountQuotaSource,
    public val cooldown: RateLimitCooldown,
    /** Startup presence seed for providers that cannot expose a persisted credential identity. */
    public val credentialPresent: Boolean = true,
    /** Account-specific identity headers, notably Kimi's device identity. */
    public val extraHeaders: CredentialHeaders? = null,
) {
    private val identitySource = TtlCredentialIdentitySource(auth as? AccountCredentialIdentitySource)
    private val credentialEligibility = AccountCredentialEligibility(identitySource, credentialPresent)

    init {
        require(AccountLabelPolicy.isSafe(label)) { "invalid OAuth account label" }
    }

    /** Re-reads this account's credential evidence OFF the sticky-session monitor; [AccountPool.select]
     *  calls it for every account before taking the lock, so the in-monitor selection reads the cache. */
    internal fun refreshCredentialEvidence() {
        identitySource.refresh()
    }

    internal fun acquireCredential(at: Long, now: AccountNow): AccountCredentialEligibility.Lease? =
        credentialEligibility.acquire(at, now)

    internal fun credentialStatus(at: Long): AccountCredentialEligibility.Status = credentialEligibility.status(at)

    internal fun markCredentialUnavailable(lease: AccountCredentialEligibility.Lease) {
        credentialEligibility.reject(lease)
    }

    internal fun markCredentialMissing(lease: AccountCredentialEligibility.Lease) {
        credentialEligibility.missing(lease)
    }

    internal fun releaseCredentialProbe(lease: AccountCredentialEligibility.Lease) {
        credentialEligibility.release(lease)
    }

    internal fun markTurnSucceeded(lease: AccountCredentialEligibility.Lease): AccountCredentialEligibility.Lease =
        credentialEligibility.succeeded(lease)

    internal fun markCredentialRefreshSucceeded(
        lease: AccountCredentialEligibility.Lease,
    ): AccountCredentialEligibility.Lease = credentialEligibility.refreshSucceeded(lease)

    internal fun resetCredentialAvailability() {
        credentialEligibility.reset()
    }
}

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

/** Why one session moved between accounts. Labels are operator-safe; no provider identity is carried. */
public data class AccountSwitch(
    val from: String,
    val to: String,
    val reason: String,
    val atEpochMillis: Long,
)

/** Immutable choice captured for an entire turn. */
public class AccountSelection internal constructor(
    public val account: PoolAccount,
    public val switch: AccountSwitch? = null,
    lease: AccountCredentialEligibility.Lease,
) {
    private val credentialLease = AtomicReference(lease)

    public val cacheCold: Boolean get() = switch != null

    /** Excludes this account from future selections without changing this turn's immutable choice. */
    public fun markCredentialUnavailable() {
        account.markCredentialUnavailable(credentialLease.get())
    }

    /** Records missing credential evidence, or a failed refresh while the credential file remains. */
    public fun markCredentialMissing() {
        account.markCredentialMissing(credentialLease.get())
    }

    /** Releases recovery-probe ownership when this selection owns it. Idempotent. */
    public fun releaseCredentialProbe() {
        account.releaseCredentialProbe(credentialLease.get())
    }

    /** A clean terminal proves this credential usable and resets its recovery series. */
    public fun markTurnSucceeded() {
        credentialLease.set(account.markTurnSucceeded(credentialLease.get()))
    }

    /** A persisted reactive refresh updates this turn's credential revision and resets recovery. */
    public fun markCredentialRefreshSucceeded() {
        credentialLease.set(account.markCredentialRefreshSucceeded(credentialLease.get()))
    }
}

/** Masked account state for status, doctor and control surfaces. */
public data class AccountView(
    val label: String,
    val primary: Boolean,
    val selected: Boolean,
    val plan: String?,
    val fiveHourUsedPercent: Double?,
    val fiveHourResetEpochSeconds: Long?,
    val sevenDayUsedPercent: Double?,
    val sevenDayResetEpochSeconds: Long?,
    val available: Boolean,
    val credentialPresent: Boolean = true,
    val authExcludedUntilEpochMillis: Long? = null,
    val authExclusionReason: String? = null,
)

/** One session's safe pool projection. */
public data class AccountPoolView(
    val selectedLabel: String?,
    val accounts: List<AccountView>,
    val lastSwitch: AccountSwitch?,
)

/** Selection could not start a turn on any account. */
public class AllAccountsExhausted(public val earliestResetEpochSeconds: Long?) : IllegalStateException(
    AccountResetText.exhausted(earliestResetEpochSeconds),
)

/** One reset timestamp vocabulary for operator responses and turn logs. */
public object AccountResetText {
    private val earliestWireInstant = Instant.parse("0000-01-01T00:00:00Z")
    private val latestWireInstant = Instant.parse("9999-12-31T23:59:59Z")

    internal fun exhausted(resetEpochSeconds: Long?): String =
        "all OAuth accounts are exhausted; earliest reset is ${format(resetEpochSeconds)}"

    /** Clamps reset evidence to the four-digit year range shared by prose and IMF-fixdate. */
    public fun normalizedInstant(resetEpochSeconds: Long): Instant =
        Instant.ofEpochSecond(
            resetEpochSeconds.coerceIn(earliestWireInstant.epochSecond, latestWireInstant.epochSecond),
        )

    public fun format(resetEpochSeconds: Long?): String {
        if (resetEpochSeconds == null) return "unknown"
        return normalizedInstant(resetEpochSeconds).toString()
    }
}
