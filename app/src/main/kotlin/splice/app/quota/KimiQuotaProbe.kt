// NEW: Kimi usage probe — GET <base>/v1/usages. Owns the URL, header map, and body parser.
package splice.app.quota

import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.auth.AuthProvider
import splice.core.usage.QuotaSlots
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.usage.SEVEN_DAYS_SECONDS
import splice.core.util.WallClock
import java.time.DateTimeException
import java.time.OffsetDateTime

internal class KimiQuotaProbe(
    client: HttpClient,
    baseUrl: String,
    auth: AuthProvider,
    clock: WallClock,
) : QuotaProbe {
    private val inner = BearerGetProbe(
        client,
        baseUrl.trimEnd('/') + "/v1/usages",
        auth,
        KimiQuotaParser(),
        clock,
        extraHeaders = mapOf("Accept" to "application/json"),
    )

    override suspend fun probe(): QuotaSnapshot? = inner.probe()
}

internal class KimiQuotaParser : QuotaParse {
    private val slots = QuotaSlots()

    override fun parse(body: JsonObject, now: Long): QuotaSnapshot? {
        val weekly = (body["usage"] as? JsonObject)?.let { remainingWindow(it, SEVEN_DAYS_SECONDS) }
        val rows = (body["limits"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val window = obj["window"] as? JsonObject
            val seconds = windowSeconds(window) ?: return@mapNotNull null
            (obj["detail"] as? JsonObject)?.let { remainingWindow(it, seconds) }
        }
        val windows = rows + listOfNotNull(weekly)
        if (windows.isEmpty()) return null
        val plan = ((body["user"] as? JsonObject)?.get("membership") as? JsonObject)?.let { str(it["level"]) }
        return slots.snapshot(windows, plan?.removePrefix("LEVEL_")?.lowercase(), now)
    }

    private fun windowSeconds(window: JsonObject?): Long? {
        val duration = int(window?.get("duration")) ?: return null
        return when (str(window?.get("timeUnit"))) {
            "TIME_UNIT_SECOND" -> duration
            "TIME_UNIT_HOUR" -> duration * SECONDS_PER_HOUR
            "TIME_UNIT_DAY" -> duration * SECONDS_PER_DAY
            else -> duration * SECONDS_PER_MINUTE
        }
    }

    private fun remainingWindow(detail: JsonObject, seconds: Long): QuotaWindow? {
        val limit = num(detail["limit"])?.takeIf { it > 0 } ?: return null
        val remaining = num(detail["remaining"]) ?: return null
        val used = ((1.0 - remaining / limit) * PERCENT).coerceIn(0.0, PERCENT)
        return QuotaWindow(used, iso(str(detail["resetTime"])), seconds)
    }

    private fun iso(text: String?): Long? {
        if (text == null) return null
        return try {
            OffsetDateTime.parse(text).toEpochSecond()
        } catch (_: DateTimeException) {
            null
        }
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

private const val PERCENT = 100.0
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_DAY = 86_400L
