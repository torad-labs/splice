package splice.accounts.pool

/** One quota window of a head's account as the control surface reads it: how full it is, when it resets (epoch
 *  SECONDS) and its own reported length in seconds. Every field is null when the account names no such window. */
public data class HeadAccountWindow(
    val usedPercent: Double? = null,
    val resetEpochSeconds: Long? = null,
    val windowSeconds: Long? = null,
)
