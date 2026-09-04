// NEW: the three usage-body shapes, each reduced to QuotaWindows and sorted into slots by length.
// Pure functions over a parsed body, so every live shape captured on 2026-09-02 is a fixture test.
package splice.app.quota

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.usage.QuotaSlots
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.usage.SEVEN_DAYS_SECONDS
import splice.core.util.Cancellables
import java.time.OffsetDateTime

internal class QuotaParsers {
    private val slots = QuotaSlots()

    /** ChatGPT `wham/usage`: primary/secondary windows by their own declared length. */
    fun codex(body: JsonObject, now: Long): QuotaSnapshot? {
        val limit = body["rate_limit"] as? JsonObject ?: return null
        val windows = listOfNotNull(
            codexWindow(limit["primary_window"], now),
            codexWindow(limit["secondary_window"], now),
        )
        if (windows.isEmpty()) return null
        return slots.snapshot(windows, str(body["plan_type"]), now)
    }

    private fun codexWindow(el: kotlinx.serialization.json.JsonElement?, now: Long): QuotaWindow? {
        val w = el as? JsonObject ?: return null
        val used = num(w["used_percent"]) ?: return null
        val reset = int(w["reset_at"]) ?: int(w["reset_after_seconds"])?.let { now / MILLIS + it }
        return QuotaWindow(used, reset, int(w["limit_window_seconds"]))
    }

    /** Kimi `coding/v1/usages`: `usage` is the weekly quota, each `limits[]` row a rate window. */
    fun kimi(body: JsonObject, now: Long): QuotaSnapshot? {
        val weekly = (body["usage"] as? JsonObject)?.let { remainingWindow(it, SEVEN_DAYS_SECONDS) }
        val rows = (body["limits"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val window = obj["window"] as? JsonObject
            val seconds = kimiWindowSeconds(window) ?: return@mapNotNull null
            (obj["detail"] as? JsonObject)?.let { remainingWindow(it, seconds) }
        }
        val windows = rows + listOfNotNull(weekly)
        if (windows.isEmpty()) return null
        val plan = ((body["user"] as? JsonObject)?.get("membership") as? JsonObject)?.let { str(it["level"]) }
        return slots.snapshot(windows, plan?.removePrefix("LEVEL_")?.lowercase(), now)
    }

    private fun kimiWindowSeconds(window: JsonObject?): Long? {
        val duration = int(window?.get("duration")) ?: return null
        return when (str(window?.get("timeUnit"))) {
            "TIME_UNIT_SECOND" -> duration
            "TIME_UNIT_HOUR" -> duration * SECONDS_PER_HOUR
            "TIME_UNIT_DAY" -> duration * SECONDS_PER_DAY
            else -> duration * SECONDS_PER_MINUTE
        }
    }

    /** `{limit, remaining, resetTime}` (strings) -> used percent of the limit. */
    private fun remainingWindow(detail: JsonObject, seconds: Long): QuotaWindow? {
        val limit = num(detail["limit"])?.takeIf { it > 0 } ?: return null
        val remaining = num(detail["remaining"]) ?: return null
        val used = ((1.0 - remaining / limit) * PERCENT).coerceIn(0.0, PERCENT)
        return QuotaWindow(used, iso(str(detail["resetTime"])), seconds)
    }

    /** SuperGrok `billing?format=credits`: one weekly period with a used percent. */
    fun grok(body: JsonObject, now: Long): QuotaSnapshot? {
        val config = body["config"] as? JsonObject ?: return null
        val used = num(config["creditUsagePercent"]) ?: return null
        val period = config["currentPeriod"] as? JsonObject
        val monthly = str(period?.get("type"))?.contains("MONTH") == true
        val seconds = if (monthly) THIRTY_DAYS_SECONDS else SEVEN_DAYS_SECONDS
        val window = QuotaWindow(used, iso(str(period?.get("end"))), seconds)
        return slots.snapshot(listOf(window), null, now)
    }

    private fun iso(text: String?): Long? = text?.let { t ->
        Cancellables.runCatchingCancellable { OffsetDateTime.parse(t).toEpochSecond() }.getOrNull()
    }

    private fun num(el: kotlinx.serialization.json.JsonElement?): Double? = (el as? JsonPrimitive)?.let { p ->
        p.doubleOrNull ?: p.content.toDoubleOrNull()
    }

    private fun int(el: kotlinx.serialization.json.JsonElement?): Long? = (el as? JsonPrimitive)?.let { p ->
        p.longOrNull ?: p.content.toLongOrNull()
    }

    private fun str(el: kotlinx.serialization.json.JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private const val MILLIS = 1000L
private const val PERCENT = 100.0
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_DAY = 86_400L
private const val THIRTY_DAYS_SECONDS = 30 * 86_400L
