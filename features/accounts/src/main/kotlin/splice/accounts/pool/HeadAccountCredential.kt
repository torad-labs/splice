package splice.accounts.pool

/** A head account's credential standing: whether splice can load it, and the timed authentication hold, if any,
 *  that keeps it out of selection, with the reason. */
public data class HeadAccountCredential(
    val present: Boolean = true,
    val excludedUntilEpochMillis: Long? = null,
    val exclusionReason: String? = null,
)
