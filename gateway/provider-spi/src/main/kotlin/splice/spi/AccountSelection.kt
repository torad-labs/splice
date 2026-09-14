// NEW: v0.4.0 FEATURES.md §11 — one account chosen once at the turn boundary.
package splice.spi

import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.QuotaSnapshot
import java.time.Instant

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
    /** False only for a missing legacy primary retained as pool topology, never for labeled files. */
    public val credentialPresent: Boolean = true,
    /** Account-specific identity headers, notably Kimi's device identity. */
    public val extraHeaders: CredentialHeaders? = null,
) {
    init {
        require(AccountLabelPolicy.isSafe(label)) { "invalid OAuth account label" }
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
public data class AccountSelection(
    val account: PoolAccount,
    val switch: AccountSwitch? = null,
) {
    public val cacheCold: Boolean get() = switch != null
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
    internal fun exhausted(resetEpochSeconds: Long?): String =
        "all OAuth accounts are exhausted; earliest reset is ${format(resetEpochSeconds)}"

    public fun format(resetEpochSeconds: Long?): String {
        if (resetEpochSeconds == null) return "unknown"
        val safeEpoch = resetEpochSeconds.coerceIn(Instant.MIN.epochSecond, Instant.MAX.epochSecond)
        return Instant.ofEpochSecond(safeEpoch).toString()
    }
}
