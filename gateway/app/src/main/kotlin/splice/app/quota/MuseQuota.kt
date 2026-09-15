// NEW: Muse subscription usage from the mint response. The poller calls MuseAuthProvider.usageFields
// (the mint the provider already owns); this file maps subs_usage into QuotaSnapshot by duration.
package splice.app.quota

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.auth.AuthProvider
import splice.core.usage.FIVE_HOURS_SECONDS
import splice.core.usage.QuotaSlots
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.usage.SEVEN_DAYS_SECONDS
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import splice.provider.muse.MuseAuthProvider
import java.time.OffsetDateTime

private const val MUSE_FIVE_HOUR_MINS = 300L

internal class MuseQuotaParser {
    private val slots = QuotaSlots()

    /** Mint body or its `subs_usage` object. A non-300-minute window never enters the five-hour slot. */
    fun parse(body: JsonObject, now: Long): QuotaSnapshot? {
        val usage = (body["subs_usage"] as? JsonObject) ?: body
        val windows = listOfNotNull(fiveHour(usage["window"] as? JsonObject), weekly(usage["weekly"] as? JsonObject))
        if (windows.isEmpty()) return null
        return slots.snapshot(windows, plan = null, now = now)
    }

    private fun fiveHour(window: JsonObject?): QuotaWindow? {
        val mins = JsonScalars.long(window, "window_duration_mins") ?: return null
        val used = usedPercent(window) ?: return null
        return if (mins == MUSE_FIVE_HOUR_MINS) {
            QuotaWindow(used, resetAt(window?.get("resets_at")), FIVE_HOURS_SECONDS)
        } else {
            null
        }
    }

    private fun weekly(window: JsonObject?): QuotaWindow? {
        val used = usedPercent(window) ?: return null
        return QuotaWindow(used, resetAt(window?.get("resets_at")), SEVEN_DAYS_SECONDS)
    }

    private fun usedPercent(window: JsonObject?): Double? {
        val el = window?.get("used_percent") as? JsonPrimitive ?: return null
        return el.doubleOrNull ?: el.content.toDoubleOrNull()
    }

    private fun resetAt(el: JsonElement?): Long? {
        val p = el as? JsonPrimitive ?: return null
        return p.longOrNull
            ?: p.content.toLongOrNull()
            ?: p.takeIf { it.isString }?.content?.let { text ->
                Cancellables.runCatchingCancellable {
                    OffsetDateTime.parse(text).toEpochSecond()
                }.getOrNull()
            }
    }
}

internal class MuseMintProbe(
    private val auth: AuthProvider,
    private val parser: MuseQuotaParser,
    private val clock: WallClock,
) : QuotaProbe {
    override suspend fun probe(): QuotaSnapshot? {
        val fields = (auth as? MuseAuthProvider)?.usageFields() ?: return null
        return parser.parse(fields, clock())
    }
}

internal class MuseQuota {
    private val parser = MuseQuotaParser()

    fun probe(auth: AuthProvider, clock: WallClock): QuotaProbe = MuseMintProbe(auth, parser, clock)
}
