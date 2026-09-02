// NEW: a provider's own rolling quota windows in one shape for every head. Codex answers with a
// 5-hour and a 7-day window, Kimi with a 5-hour window and a weekly quota, SuperGrok with a weekly
// period, Anthropic with its unified 5h/7d headers. Claude Code knows exactly two slots
// (five_hour, seven_day) and draws them as the bars on its status line, so the slots are named for
// those, and a window lands in a slot by its LENGTH, never by the provider's own name for it (a Pro
// Codex plan calls its weekly window "primary").
package splice.core.usage

/** One rolling window as the provider reports it: how much is used, when it resets, how long it is. */
public data class QuotaWindow(
    val usedPercent: Double,
    /** Epoch SECONDS. Null when the provider gave no reset at all. */
    val resetsAt: Long?,
    val windowSeconds: Long?,
)

public data class QuotaSnapshot(
    val fiveHour: QuotaWindow? = null,
    val sevenDay: QuotaWindow? = null,
    val plan: String? = null,
    /** Epoch millis of the observation. */
    val updatedAt: Long = 0L,
) {
    public val isEmpty: Boolean get() = fiveHour == null && sevenDay == null
}

/** Sorts a provider's windows into the two slots by length: anything up to six hours is the
 *  five-hour slot, anything longer the seven-day one. First window wins per slot. */
public class QuotaSlots {
    public fun snapshot(windows: List<QuotaWindow>, plan: String?, now: Long): QuotaSnapshot {
        val five = windows.firstOrNull { (it.windowSeconds ?: 0L) in 1..FIVE_HOUR_SLOT_MAX_SECONDS }
        val seven = windows.firstOrNull { (it.windowSeconds ?: 0L) > FIVE_HOUR_SLOT_MAX_SECONDS }
        return QuotaSnapshot(five, seven, plan, now)
    }
}

public const val FIVE_HOURS_SECONDS: Long = 5 * 3600L
public const val SEVEN_DAYS_SECONDS: Long = 7 * 24 * 3600L
private const val FIVE_HOUR_SLOT_MAX_SECONDS: Long = 6 * 3600L
