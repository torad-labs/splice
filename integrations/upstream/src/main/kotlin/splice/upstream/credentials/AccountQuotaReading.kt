package splice.upstream.credentials

/** An account's quota as a status surface reads it: its two windows and when they were observed, epoch SECONDS (the
 *  reset fields' unit), or null when its tracker names no observation. */
public data class AccountQuotaReading(
    val fiveHour: AccountWindowReading = AccountWindowReading(),
    val sevenDay: AccountWindowReading = AccountWindowReading(),
    val observedAtEpochSeconds: Long? = null,
)
