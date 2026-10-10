// The quota half of a console account row: its two windows, plan and read time, and how they are written as the
// row's JSON fields. Split from AccountsRoute so the route joins accounts and this says what a quota looks like.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import splice.core.usage.ModelQuota
import splice.core.usage.QuotaJson
import splice.core.usage.QuotaWindowView as PlanWindow

/** One quota window's percent, reset and (V4-132) its own reported LENGTH — [AccountPool]'s own
 *  [splice.upstream.credentials.AccountView] carries the same three fields; this is the console-payload copy of
 *  that shape, grouped so [JoinedQuota] carries five-hour and seven-day as ONE field each instead of three. */
internal data class QuotaWindowView(val usedPercent: Double?, val resetEpochSeconds: Long?, val windowSeconds: Long?)

/** A joined row's plan, its two quota windows, and when they were read, from whichever source carried them. */
internal data class JoinedQuota(
    val plan: String?,
    val fiveHour: QuotaWindowView,
    val sevenDay: QuotaWindowView,
    /** Epoch SECONDS the quota was read, from whichever source carried it — null when it didn't. */
    val observedAtEpochSeconds: Long?,
    /** Each model's own weekly window, where the provider reports one (Claude). */
    val sevenDayModels: List<ModelQuota> = emptyList(),
    /** Epoch SECONDS the provider last answered with no usage for this account ([QuotaView.noUsageAt]). */
    val noUsageAt: Long? = null,
) {
    /** The quota fields of a row's JSON. */
    fun writeInto(into: JsonObjectBuilder, nowSeconds: Long) {
        into.put("five_hour_limit_percent", fiveHour.usedPercent?.let { 100 })
        into.put("seven_day_limit_percent", sevenDay.usedPercent?.let { 100 })
        into.put("plan", plan)
        into.put("five_hour_used_percent", fiveHour.usedPercent)
        into.put("five_hour_reset_epoch_seconds", fiveHour.resetEpochSeconds)
        into.put("five_hour_window_seconds", fiveHour.windowSeconds)
        into.put("five_hour_current", current(fiveHour, nowSeconds))
        into.put("seven_day_used_percent", sevenDay.usedPercent)
        into.put("seven_day_reset_epoch_seconds", sevenDay.resetEpochSeconds)
        into.put("seven_day_window_seconds", sevenDay.windowSeconds)
        into.put("seven_day_current", current(sevenDay, nowSeconds))
        QuotaJson().putModels(into, "seven_day_models", sevenDayModels)
        into.put("observed_at_epoch_seconds", observedAtEpochSeconds)
        into.put("no_usage_at_epoch_seconds", noUsageAt)
    }

    /** V4-407: whether a window may count as the plan's usage now. The figures still ship either way: the Accounts
     *  page shows an old reading with its age, while the nearest limit reads current windows only, so a reading
     *  hours old is never ranked as the fleet's limit. */
    private fun current(window: QuotaWindowView, nowSeconds: Long): Boolean {
        val used = window.usedPercent ?: return false
        return PlanWindow(used.toInt(), window.resetEpochSeconds, observedAtEpochSeconds).currentAt(nowSeconds) != null
    }
}
