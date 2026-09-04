// NEW: the plan-usage half of the status line — the segments the operator's own script drew on
// the native Claude head and every splice head now gets: effort beside the model, the session's
// spend, and the 5h / 7d windows as bars with the reset time once a bar is worth acting on.
// Sources, in order: Claude Code's own `rate_limits` (it read them off the unified headers the head
// sent, so they are already this head's windows), else the head's tracked quota straight from the
// daemon (the first tick of a session, before any response carried headers).
package splice.control

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class StatuslineBars(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val resetFormat = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT)

    fun effort(root: JsonObject): String? = str((root["effort"] as? JsonObject)?.get("level"))

    fun costSegment(root: JsonObject): String? {
        val cost = num((root["cost"] as? JsonObject)?.get("total_cost_usd"))?.takeIf { it > 0.0 } ?: return null
        return "$DIM\$$RESET" + String.format(Locale.ROOT, "%.2f", cost)
    }

    fun limitSegments(root: JsonObject, quota: QuotaView?): List<String> {
        val limits = root["rate_limits"] as? JsonObject
        val five = window(limits, "five_hour") ?: quota?.fiveHour?.let { it.usedPct to it.resetsAt }
        val seven = window(limits, "seven_day") ?: quota?.sevenDay?.let { it.usedPct to it.resetsAt }
        return listOfNotNull(segment("5h", five), segment("7d", seven))
    }

    private fun window(limits: JsonObject?, key: String): Pair<Int, Long?>? {
        val w = limits?.get(key) as? JsonObject ?: return null
        val pct = num(w["used_percentage"])?.toInt() ?: return null
        return pct to (w["resets_at"] as? JsonPrimitive)?.longOrNull
    }

    private fun segment(label: String, window: Pair<Int, Long?>?): String? {
        val (pct, resetsAt) = window ?: return null
        val c = color(pct)
        val reset = resetsAt?.takeIf { pct >= WARN_PCT }?.let { at ->
            "$DIM→${resetFormat.format(Instant.ofEpochSecond(at).atZone(zone))}$RESET"
        }.orEmpty()
        return "$DIM$label$RESET $c${bar(pct)}$RESET $c$pct%$RESET$reset"
    }

    /** green under 60, yellow 60-84, bold red 85 and up — the operator's own thresholds. */
    fun color(pct: Int): String = when {
        pct >= CRITICAL_PCT -> BOLD_RED
        pct >= WARN_PCT -> YELLOW
        else -> GREEN
    }

    fun bar(pct: Int, width: Int = BAR_WIDTH): String {
        val filled = (pct.coerceIn(0, PERCENT) * width + PERCENT / 2) / PERCENT
        return "█".repeat(filled) + "░".repeat(width - filled)
    }

    private fun num(el: kotlinx.serialization.json.JsonElement?): Double? = (el as? JsonPrimitive)?.doubleOrNull

    private fun str(el: kotlinx.serialization.json.JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
}

private const val RESET = "[0m"
private const val DIM = "[2m"
private const val GREEN = "[32m"
private const val YELLOW = "[33m"
private const val BOLD_RED = "[1;31m"
private const val BAR_WIDTH = 8
private const val PERCENT = 100
private const val WARN_PCT = 60
private const val CRITICAL_PCT = 85
