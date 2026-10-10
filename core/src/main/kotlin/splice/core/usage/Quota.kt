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

/** One model's own share of the weekly window (Claude's Opus and Sonnet weeks): the model as the provider names it
 *  and how much of it is used. It rides the weekly window and resets with it. */
public data class ModelQuota(val model: String, val usedPercent: Double)

public data class QuotaSnapshot(
    val fiveHour: QuotaWindow? = null,
    val sevenDay: QuotaWindow? = null,
    val plan: String? = null,
    /** Epoch millis of the observation. */
    val updatedAt: Long = 0L,
    /** The per-model weekly windows, where the provider reports them (Claude); empty everywhere else. */
    val models: List<ModelQuota> = emptyList(),
) {
    public val isEmpty: Boolean get() = fiveHour == null && sevenDay == null

    /** A provider that answered its usage endpoint with no usage for this account: no windows, but a time. A probe
     *  returns it, and a tracker keeps it in place of an older reading (Marlin's ruling, Oct 10, 2026). */
    public val answeredEmpty: Boolean get() = isEmpty && updatedAt > 0L

    /** A turn's header reading names no model weeks; only the provider's usage endpoint does. So a reading without them
     *  keeps [earlier]'s while both read the same week (the same weekly reset), and drops them once the week rolls. */
    public fun keepingModelsOf(earlier: QuotaSnapshot?): QuotaSnapshot {
        val reset = sevenDay?.resetsAt
        val prior = earlier?.sevenDay?.resetsAt
        // the endpoint rounds its reset to the second and the headers carry their own, so one week reads within a minute
        val sameWeek = reset != null && prior != null && kotlin.math.abs(reset - prior) <= SAME_RESET_SECONDS
        return if (models.isEmpty() && sameWeek) copy(models = earlier.models) else this
    }

    /** V4-452: the window this snapshot's CURRENT reading at [nowMillis] names fully used, and its reset; the
     *  later-resetting one when both are. Null when none is, or when a full window names no reset: a reading with
     *  no instant says nothing to report. */
    public fun fullAt(nowMillis: Long): QuotaFull? {
        val current = currentAt(nowMillis) ?: return null
        val full = listOfNotNull(
            current.fiveHour?.let { QuotaFullWindow.FIVE_HOUR to it },
            current.sevenDay?.let { QuotaFullWindow.SEVEN_DAY to it },
        ).filter { (_, window) -> window.usedPercent >= FULLY_USED_PERCENT }
        val reset = full.map { (name, window) -> QuotaFull(name, window.resetsAt ?: return null) }
        return reset.maxByOrNull { it.resetsAtEpochSeconds }
    }

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
        val week = sevenDay?.let(current)
        val byModel = if (week == null) emptyList() else models
        return copy(fiveHour = fiveHour?.let(current), sevenDay = week, models = byModel).takeUnless { it.isEmpty }
    }
}

/** V4-327: a window reading is shown as current only while it is younger than [FOR_SECONDS] and its
 *  window has not reset. A reading of unknown age is not current, and a window past its reset has
 *  started over with nothing read since: its figure is neither the old one nor 0. Take-resume-5's
 *  first status rows read "5h 0%  7d 52%" from a snapshot hours old while the plan stood at 71% and
 *  98%, beside Claude Code's own "98% of your weekly limit". All times in epoch SECONDS. */
public object QuotaFreshness {
    /** Three of QuotaPoller's five-minute polls. Plan windows and token-limit headers share
     *  this expiry: neither can claim a current limit from an old observation. */
    public const val FOR_SECONDS: Long = 15 * 60L

    public fun current(observedAt: Long?, resetsAt: Long?, nowSeconds: Long): Boolean =
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

/** V4-452: a plan window the provider's own current reading names fully used, and when it resets (epoch SECONDS).
 *  A READING, never a refusal: on Oct 1 claudex served 1,163 turns while its week read 100%, so a surface reports it
 *  beside a head that stays ready. Only a refusal splice holds (V4-398/V4-412) says out of quota. */
public data class QuotaFull(val window: QuotaFullWindow, val resetsAtEpochSeconds: Long)

/** The two slots a reading can name full. [wire] is the slot's name on every payload that carries one. */
public enum class QuotaFullWindow(public val wire: String) {
    FIVE_HOUR("five_hour"),
    SEVEN_DAY("seven_day"),
}

// why: the providers report a full window as exactly 100 (AccountPool's own gate reads the same line), and a
// figure under it is not a statement that the plan is spent
private const val FULLY_USED_PERCENT = 100.0

// why: two readings of one weekly window, from the usage endpoint and from a turn's headers, name its reset within a
// minute of each other; a week that rolled names one days later
private const val SAME_RESET_SECONDS = 60L

public const val FIVE_HOURS_SECONDS: Long = 5 * 3600L
public const val SEVEN_DAYS_SECONDS: Long = 7 * 24 * 3600L
public const val FIVE_HOUR_SLOT_MAX_SECONDS: Long = 6 * 3600L
