package splice.app.control

/** The usage levels at which a head is flagged as running hot. */
public data class UsageWarning(
    val warnPct: Int,
    val warnTokens5h: Long,
)
