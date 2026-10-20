// NEW: v0.4.0 FEATURES.md §11 — one account chosen once at the turn boundary.
// V4-160 (concentration, 2026-09-18): TtlCredentialIdentitySource moved verbatim to its own file.
// The views status surfaces read (AccountView and the readings inside it) live in AccountViews.kt.
package splice.upstream.credentials

import kotlinx.coroutines.runInterruptible
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.QuotaSnapshot
import splice.core.util.LocalTimeText
import splice.core.util.WallClock
import splice.upstream.CredentialHeaders
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.WarmLoginWait
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** One shared syntax gate for labels at persistence and selection boundaries. */
public object AccountLabelPolicy {
    private val safe = Regex("[a-z0-9][a-z0-9._-]{0,47}")

    public fun isSafe(label: String): Boolean = safe.matches(label)

    /** Native selector ids have their own namespace; credential-folder labels never admit a colon. */
    public fun isSelector(label: String): Boolean =
        isSafe(label) || label.startsWith("native:") && isSafe(label.removePrefix("native:"))
}

/** Reconciles externally replaced credentials without resetting existing session choices or leased turns. */
public fun interface AccountMembershipRefresh {
    public fun refresh()
}

/** Reads one account's latest provider quota without coupling the SPI to gateway persistence. */
public fun interface AccountQuotaSource {
    public fun snapshot(): QuotaSnapshot?

    /** A provider-proved refusal shared by credentials of the same account, never inferred from a full reading. */
    public val held: Boolean get() = false
}

// V4-101: AccountNow IS GONE. This module names [splice.core.util.WallClock] directly — the same role
// the alias stood for: a reading of calendar time in epoch milliseconds, used to compare provider
// reset timestamps without a process-global clock in policy code.
//
// V4-122 reconciled the second declaration onto the core one and kept a typealias so every call site
// (SAM conversion included) compiled unchanged. That was the right intermediate and the wrong
// destination: an alias is still a second spelling of one role, and this row exists to leave one
// name. The registry note that marked this pair DELIBERATELY ABSENT was correct, and it is discharged
// by the merge rather than by prose about it.

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
    private data class QuotaObservation(val snapshot: QuotaSnapshot?, val held: Boolean)

    @Volatile
    private var quotaObservation = QuotaObservation(quota.snapshot(), quota.held)
    internal val quotaSnapshot: QuotaSnapshot? get() = quotaObservation.snapshot
    internal val quotaHeld: Boolean get() = quotaObservation.held
    private val identitySource = TtlCredentialIdentitySource(auth as? AccountCredentialIdentitySource)
    private val credentialEligibility = AccountCredentialEligibility(identitySource, credentialPresent)

    init {
        require(AccountLabelPolicy.isSelector(label)) { "invalid OAuth account label" }
    }

    /** Re-reads credential, quota and proved standing OFF the sticky-session monitor; [AccountPool.select]
     *  calls it for every account before taking the lock, so the in-monitor selection reads only cached facts. */
    internal fun refreshCredentialEvidence() {
        identitySource.refresh()
        quotaObservation = QuotaObservation(quota.snapshot(), quota.held)
    }

    /** Shares only the raw observation. Quota and standing remain a fresh read for this caller. */
    internal suspend fun refreshCredentialEvidence(loads: AccountEvidenceLoads) {
        loads.refresh(identitySource)
        runInterruptible(loads.ioDispatcher) {
            quotaObservation = QuotaObservation(quota.snapshot(), quota.held)
        }
    }

    internal fun acquireCredential(at: Long, now: WallClock): AccountCredentialEligibility.Lease? =
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
    ): AccountCredentialEligibility.Lease {
        identitySource.invalidate()
        identitySource.refresh()
        return credentialEligibility.refreshSucceeded(lease)
    }

    internal fun resetCredentialAvailability() {
        credentialEligibility.reset()
    }
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
    /** The head's own switch this choice made, once per change of the login it serves; [switch] is this session's. */
    public val headSwitch: AccountSwitch? = null,
    /** The session stays on the login it was last served on within the hour, so its prompt cache may be warm there:
     *  admission and the retry loop wait a short rate limit out on this login, up to [WARM_LOGIN_WAIT_MS] in all
     *  ([warmWait]), rather than move the turn and rebuild the cache elsewhere. */
    public val warm: Boolean = false,
    /** The session's served clock on this login, stamped when a turn ends cleanly on it ([markTurnSucceeded]). */
    private val served: ServedStamp? = null,
) {
    private val credentialLease = AtomicReference(lease)

    /** This turn's one wait for rate limits on its own login, spent by admission and by every post of the turn;
     *  null for a cold turn, which moves to a free login instead of waiting. */
    public val warmWait: WarmLoginWait? = if (warm) WarmLoginWait(WARM_LOGIN_WAIT_MS) else null

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

    /** A clean terminal proves this credential usable, resets its recovery series, and renews the session's warm hour:
     *  this turn served the session on this login, which refreshed its prompt cache there. */
    public fun markTurnSucceeded() {
        credentialLease.set(account.markTurnSucceeded(credentialLease.get()))
        served?.stamp()
    }

    /** A persisted reactive refresh updates this turn's credential revision and resets recovery. */
    public fun markCredentialRefreshSucceeded() {
        credentialLease.set(account.markCredentialRefreshSucceeded(credentialLease.get()))
    }
}

/** One reset timestamp vocabulary for operator responses and turn logs. [format] is the ISO instant a log or journal
 *  line carries; [forPerson] says the same instant the way a person reads it, for a sentence a client prints. */
public object AccountResetText {
    private val earliestWireInstant = Instant.parse("0000-01-01T00:00:00Z")
    private val latestWireInstant = Instant.parse("9999-12-31T23:59:59Z")

    internal fun exhausted(resetEpochSeconds: Long?): String =
        "all OAuth accounts are exhausted; earliest reset is ${forPerson(resetEpochSeconds)}"

    /** Clamps reset evidence to the four-digit year range shared by prose and IMF-fixdate. */
    public fun normalizedInstant(resetEpochSeconds: Long): Instant =
        Instant.ofEpochSecond(
            resetEpochSeconds.coerceIn(earliestWireInstant.epochSecond, latestWireInstant.epochSecond),
        )

    public fun format(resetEpochSeconds: Long?): String {
        if (resetEpochSeconds == null) return "unknown"
        return normalizedInstant(resetEpochSeconds).toString()
    }

    /** V4-433: [resetEpochSeconds] as "Oct 4, 7:00 PM CDT" in the machine's zone as of this call, or "unknown" when no
     *  reset was named; clamped to the same year range as [format]. [times] names a zone for a test. */
    public fun forPerson(resetEpochSeconds: Long?, times: LocalTimeText = LocalTimeText()): String {
        if (resetEpochSeconds == null) return "unknown"
        return times.at(normalizedInstant(resetEpochSeconds).epochSecond)
    }
}
