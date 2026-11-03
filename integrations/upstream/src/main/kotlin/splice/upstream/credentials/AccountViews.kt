// NEW: the account state status, doctor and control surfaces read (a view of the pool, an account, its quota and its
// credential standing), moved verbatim from AccountSelection.kt, which keeps the selection itself. Same package: no call
// site changes.
package splice.upstream.credentials

import splice.core.usage.ModelQuota
import splice.core.usage.PlanLimit

/** Masked account state for status, doctor and control surfaces. */
public data class AccountView(
    val label: String,
    val primary: Boolean,
    val selected: Boolean,
    val plan: String?,
    val available: Boolean,
    /** Both quota windows and their observation time; V4-132 added each window's own reported length. */
    val quota: AccountQuotaReading = AccountQuotaReading(),
    val credential: AccountCredentialReading = AccountCredentialReading(),
)

/** The plan windows the provider named spent on an account while they hold: the whole login's, which refuses every
 *  turn, and each model's week, which refuses that model's turns alone, the soonest reset first. */
public data class AccountHolds(val wholeLogin: PlanLimit? = null, val models: List<PlanLimit> = emptyList())

/** An account's quota as a status surface reads it: its two windows and when they were observed, epoch SECONDS (the
 *  reset fields' unit), or null when its tracker names no observation. */
public data class AccountQuotaReading(
    val fiveHour: AccountWindowReading = AccountWindowReading(),
    val sevenDay: AccountWindowReading = AccountWindowReading(),
    val observedAtEpochSeconds: Long? = null,
    /** Each model's own weekly window, where the provider reports one (Claude). */
    val sevenDayModels: List<ModelQuota> = emptyList(),
    /** Epoch SECONDS the provider last answered with no usage for this account (QuotaSnapshot.answeredEmpty). */
    val noUsageAtEpochSeconds: Long? = null,
    /** The plan windows the provider named spent on this account, as it named them: [AccountHolds.wholeLogin] holds it
     *  for every model, each of [AccountHolds.models] for one model alone (Nov 3, 2026). */
    val holds: AccountHolds = AccountHolds(),
)

/** One quota window of an account as a status surface reads it: how full it is, when it resets (epoch SECONDS) and
 *  its own reported length in seconds. Every field is null when the account's tracker names no such window. */
public data class AccountWindowReading(
    val usedPercent: Double? = null,
    val resetEpochSeconds: Long? = null,
    val windowSeconds: Long? = null,
)

/** An account's credential standing as a status surface reads it: whether splice can load it, and the timed
 *  authentication hold, if any, that keeps it out of selection, with the reason. */
public data class AccountCredentialReading(
    val present: Boolean = true,
    val excludedUntilEpochMillis: Long? = null,
    val exclusionReason: String? = null,
)

/** One session's safe pool projection. */
public data class AccountPoolView(
    val selectedLabel: String?,
    val accounts: List<AccountView>,
    val lastSwitch: AccountSwitch?,
    /** The selector's blocking horizon for each account, in epoch seconds; absent means no reported deadline. */
    val blockedUntilEpochSecondsByLabel: Map<String, Long> = emptyMap(),
    /** The login the command moves to when the one it would use next runs out; null when none could serve. */
    val followingLabel: String? = null,
)
