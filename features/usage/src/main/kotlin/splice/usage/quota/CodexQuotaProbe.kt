// NEW: ChatGPT usage probe — GET origin/backend-api/wham/usage. Owns the URL, header map, and body
// parser; the shared GET is vendor-blind.
package splice.usage.quota

import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.auth.AuthProvider
import splice.core.usage.EPOCH_MILLIS_FLOOR
import splice.core.usage.QuotaSlots
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.WallClock
import java.net.URI

internal class CodexQuotaProbe(
    client: HttpClient,
    baseUrl: String,
    auth: AuthProvider,
    clock: WallClock,
) : QuotaProbe {
    private val inner = BearerGetProbe(
        client,
        URI(baseUrl).resolve("/backend-api/wham/usage").toString(),
        auth,
        CodexQuotaParser(),
        clock,
        extraHeaders = mapOf("Accept" to "application/json"),
    )

    override suspend fun probe(): QuotaSnapshot? = inner.probe()
}

internal class CodexQuotaParser : QuotaParse {
    private val slots = QuotaSlots()

    override fun parse(body: JsonObject, now: Long): QuotaSnapshot? {
        val limit = body["rate_limit"] as? JsonObject ?: return null
        val windows = listOfNotNull(
            window(limit["primary_window"], now),
            window(limit["secondary_window"], now),
        )
        if (windows.isEmpty()) return null
        return slots.snapshot(windows, str(body["plan_type"]), now)
    }

    private fun window(el: kotlinx.serialization.json.JsonElement?, now: Long): QuotaWindow? {
        val w = el as? JsonObject ?: return null
        val used = num(w["used_percent"]) ?: return null
        val reset = int(w["reset_at"])?.let(::epochSeconds)
            ?: int(w["reset_after_seconds"])?.let { now / MILLIS + it }
        return QuotaWindow(used, reset, int(w["limit_window_seconds"]))
    }

    private fun num(el: kotlinx.serialization.json.JsonElement?): Double? = (el as? JsonPrimitive)?.let { p ->
        p.doubleOrNull ?: p.content.toDoubleOrNull()
    }

    private fun int(el: kotlinx.serialization.json.JsonElement?): Long? = (el as? JsonPrimitive)?.let { p ->
        p.longOrNull ?: p.content.toLongOrNull()
    }

    private fun str(el: kotlinx.serialization.json.JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun epochSeconds(value: Long): Long =
        if (value < EPOCH_MILLIS_FLOOR) value else value / MILLIS
}

private const val MILLIS = 1000L
