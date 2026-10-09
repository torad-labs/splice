package splice.usage

/** The thresholds at which a head's usage turns to a warning. */
public data class UsageHeadWarn(
    val warnPct: Int,
    val warnTokens5h: Long,
)
