package splice.upstream.credentials

/** An account's credential standing as a status surface reads it: whether splice can load it, and the timed
 *  authentication hold, if any, that keeps it out of selection, with the reason. */
public data class AccountCredentialReading(
    val present: Boolean = true,
    val excludedUntilEpochMillis: Long? = null,
    val exclusionReason: String? = null,
)
