package splice.upstream.credentials

/** One quota window of an account as a status surface reads it: how full it is, when it resets (epoch SECONDS) and
 *  its own reported length in seconds. Every field is null when the account's tracker names no such window. */
public data class AccountWindowReading(
    val usedPercent: Double? = null,
    val resetEpochSeconds: Long? = null,
    val windowSeconds: Long? = null,
)
