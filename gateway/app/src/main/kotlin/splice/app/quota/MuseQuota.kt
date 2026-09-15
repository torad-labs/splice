// NEW: Muse subscription usage from the mint response. The poller calls UsageFields (wired to
// MuseAuthProvider.usageFields); this file maps subs_usage into QuotaSnapshot by duration.
package splice.app.quota

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.usage.FIVE_HOUR_SLOT_MAX_SECONDS
import splice.core.usage.QuotaSlots
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import splice.provider.muse.MuseAuthProvider
import java.time.DateTimeException
import java.time.OffsetDateTime

internal fun interface UsageFields {
    suspend fun invoke(): JsonObject?
}

internal class MuseQuotaParser {
    private val slots = QuotaSlots()

    /** Mint body or its `subs_usage` object. A window longer than six hours never enters the 5h slot. */
    fun parse(body: JsonObject, now: Long): QuotaSnapshot? {
        val usage = (body["subs_usage"] as? JsonObject) ?: body
        val windows = listOfNotNull(
            fiveHour(usage["window"] as? JsonObject),
            weekly(usage["weekly"] as? JsonObject, now),
        )
        if (windows.isEmpty()) return null
        return slots.snapshot(windows, plan = null, now = now)
    }

    private fun fiveHour(window: JsonObject?): QuotaWindow? {
        val mins = JsonScalars.long(window, "window_duration_mins") ?: return null
        val used = usedPercent(window) ?: return null
        val seconds = mins * SECONDS_PER_MINUTE
        return if (seconds in 1..FIVE_HOUR_SLOT_MAX_SECONDS) {
            QuotaWindow(used, resetAt(window?.get("resets_at")), seconds)
        } else {
            null
        }
    }

    private fun weekly(window: JsonObject?, now: Long): QuotaWindow? {
        val used = usedPercent(window) ?: return null
        val reset = resetAt(window?.get("resets_at"))
        return QuotaWindow(used, reset, slots.weeklyWindowSeconds(reset, now))
    }

    private fun usedPercent(window: JsonObject?): Double? {
        val el = window?.get("used_percent") as? JsonPrimitive ?: return null
        val n = el.doubleOrNull ?: el.content.toDoubleOrNull() ?: return null
        return n.coerceIn(0.0, PERCENT)
    }

    private fun resetAt(el: JsonElement?): Long? {
        val p = el as? JsonPrimitive ?: return null
        val asLong = p.longOrNull ?: p.content.toLongOrNull()
        return asLong?.let(::epochSeconds) ?: parseResetInstant(p)
    }

    private fun parseResetInstant(p: JsonPrimitive): Long? {
        if (!p.isString) return null
        return try {
            OffsetDateTime.parse(p.content).toEpochSecond()
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun epochSeconds(value: Long): Long =
        if (value < EPOCH_MILLIS_FLOOR) value else value / MILLIS
}

internal class MuseMintProbe(
    private val fields: UsageFields,
    private val parser: MuseQuotaParser,
    private val clock: WallClock,
) : QuotaProbe {
    override suspend fun probe(): QuotaSnapshot? {
        val body = fields.invoke() ?: return null
        return parser.parse(body, clock())
    }
}

internal class MuseAuthUsageFields(private val auth: MuseAuthProvider) : UsageFields {
    override suspend fun invoke(): JsonObject? = auth.usageFields()
}

private const val SECONDS_PER_MINUTE = 60L
private const val PERCENT = 100.0
private const val MILLIS = 1000L
private const val EPOCH_MILLIS_FLOOR = 100_000_000_000L
