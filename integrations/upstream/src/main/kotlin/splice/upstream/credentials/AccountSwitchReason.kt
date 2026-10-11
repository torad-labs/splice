// NEW: Pool-produced switch reasons, split out of AccountPool.kt: the wording the pool stamps on an account switch.
package splice.upstream.credentials

/** Pool-produced switch reasons, including named provider windows and legacy persisted wording. */
public object AccountSwitchReason {
    internal const val PINNED = "operator pinned this account"
    internal const val ORDERED = "operator account order"
    internal const val PRIMARY_RESET = "primary account reset"
    internal const val RESET_SOONER = "quota resets sooner"
    internal const val PROVIDER_LIMIT = "provider rate limit reached"
    internal const val WAIT_BUDGET = "rate limit exceeds turn wait budget"
    internal const val FIVE_HOUR_QUOTA = "5-hour quota exhausted"
    internal const val SEVEN_DAY_QUOTA = "7-day quota exhausted"
    internal const val USAGE_READING_FULL = "quota usage reading full"
    internal const val ACCOUNT_UNAVAILABLE_REASON = "account unavailable"
    internal const val SIGN_IN_NEEDED = "previous login needs sign-in"

    private val reasons = setOf(
        PINNED,
        ORDERED,
        PRIMARY_RESET,
        RESET_SOONER,
        PROVIDER_LIMIT,
        WAIT_BUDGET,
        FIVE_HOUR_QUOTA,
        SEVEN_DAY_QUOTA,
        USAGE_READING_FULL,
        ACCOUNT_UNAVAILABLE_REASON,
        SIGN_IN_NEEDED,
        planLimit("5-hour"),
        planLimit("7-day"),
        "7d window exhausted",
    )
    private val planWindow = Regex("7-day [A-Za-z0-9][A-Za-z0-9 -]{0,63} plan limit reached")

    internal fun planLimit(windowWords: String): String = "$windowWords plan limit reached"

    /** Foreign prose and terminal controls never become printable switch reasons. */
    public fun isSafe(reason: String): Boolean = reason in reasons || planWindow.matches(reason)
}
