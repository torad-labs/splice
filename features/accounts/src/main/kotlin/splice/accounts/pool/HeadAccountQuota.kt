package splice.accounts.pool

/** A head account's quota: its two windows and when they were observed, epoch SECONDS (the reset fields' unit), or
 *  null when its tracker names no observation. */
public data class HeadAccountQuota(
    val fiveHour: HeadAccountWindow = HeadAccountWindow(),
    val sevenDay: HeadAccountWindow = HeadAccountWindow(),
    val observedAtEpochSeconds: Long? = null,
)
