// NEW: a provider's own rolling quota windows in one shape for every head. Codex answers with a
// 5-hour and a 7-day window, Kimi with a 5-hour window and a weekly quota, SuperGrok with a weekly
// period, Anthropic with its unified 5h/7d headers. Claude Code knows exactly two slots
// (five_hour, seven_day) and draws them as the bars on its status line, so the slots are named for
// those, and a window lands in a slot by its LENGTH, never by the provider's own name for it (a Pro
// Codex plan calls its weekly window "primary").
package splice.core.usage

import java.util.concurrent.TimeUnit

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

    /** When both windows were observed, in epoch SECONDS — the unit [QuotaWindow.resetsAt] carries,
     *  so a surface can print the pair side by side. Null when the snapshot names no observation: 0
     *  is what a file written before `updated_at` decodes to, and it would read as 1970. */
    public val observedAtEpochSeconds: Long?
        get() = updatedAt.takeIf { it > 0L }?.let(TimeUnit.MILLISECONDS::toSeconds)

    /** The windows that may be shown as the plan's usage at [nowMillis] ([QuotaFreshness]), or null
     *  when none may. What a client response and the status line carry: never the snapshot a
     *  previous daemon run persisted, hours old (V4-327). */
    public fun currentAt(nowMillis: Long): QuotaSnapshot? {
        val nowSeconds = TimeUnit.MILLISECONDS.toSeconds(nowMillis)
        val observed = observedAtEpochSeconds
        val current = { w: QuotaWindow -> w.takeIf { QuotaFreshness.current(observed, it.resetsAt, nowSeconds) } }
        return copy(fiveHour = fiveHour?.let(current), sevenDay = sevenDay?.let(current)).takeUnless { it.isEmpty }
    }
}

/** V4-327: a window reading is shown as current only while it is younger than [FOR_SECONDS] and its
 *  window has not reset. A reading of unknown age is not current, and a window past its reset has
 *  started over with nothing read since: its figure is neither the old one nor 0. Take-resume-5's
 *  first status rows read "5h 0%  7d 52%" from a snapshot hours old while the plan stood at 71% and
 *  98%, beside Claude Code's own "98% of your weekly limit". All times in epoch SECONDS. */
internal object QuotaFreshness {
    /** Three of QuotaPoller's five-minute polls. A passthrough head reads the windows off every
     *  round, so only an idle head or a failing probe gets this old. */
    const val FOR_SECONDS: Long = 15 * 60L

    fun current(observedAt: Long?, resetsAt: Long?, nowSeconds: Long): Boolean =
        observedAt != null && nowSeconds - observedAt <= FOR_SECONDS && (resetsAt == null || resetsAt > nowSeconds)
}

/** Sorts a provider's windows into the two slots by length: anything up to six hours is the
 *  five-hour slot, anything longer the seven-day one. First window wins per slot. */
public class QuotaSlots {
    public fun snapshot(windows: List<QuotaWindow>, plan: String?, now: Long): QuotaSnapshot {
        val five = windows.firstOrNull { (it.windowSeconds ?: 0L) in 1..FIVE_HOUR_SLOT_MAX_SECONDS }
        val seven = windows.firstOrNull { (it.windowSeconds ?: 0L) > FIVE_HOUR_SLOT_MAX_SECONDS }
        return QuotaSnapshot(five, seven, plan, now)
    }

    /**
     * Sanctioned exception: a window the provider names weekly and puts no duration on the wire
     * gets windowSeconds from resets_at minus now. If that remaining span is still within the
     * five-hour ceiling (the week is about to roll), it still occupies the seven-day slot — the
     * name is the duration the wire omitted.
     */
    public fun weeklyWindowSeconds(resetsAt: Long?, nowMillis: Long): Long {
        val remaining = resetsAt?.minus(nowMillis / 1000L) ?: return SEVEN_DAYS_SECONDS
        return if (remaining > FIVE_HOUR_SLOT_MAX_SECONDS) remaining else SEVEN_DAYS_SECONDS
    }
}

public const val FIVE_HOURS_SECONDS: Long = 5 * 3600L
public const val SEVEN_DAYS_SECONDS: Long = 7 * 24 * 3600L
public const val FIVE_HOUR_SLOT_MAX_SECONDS: Long = 6 * 3600L
