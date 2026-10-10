// NEW: SuperGrok billing probe — GET cli-chat-proxy.grok.com/v1/billing?format=credits. Owns the
// URL, the xAI header map, and the body parser.
package splice.usage.quota

import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import splice.core.auth.AuthProvider
import splice.core.usage.QuotaSlots
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.usage.SEVEN_DAYS_SECONDS
import splice.core.util.WallClock
import java.time.DateTimeException
import java.time.OffsetDateTime

internal class GrokQuotaProbe(
    client: HttpClient,
    auth: AuthProvider,
    clock: WallClock,
) : QuotaProbe {
    private val inner = BearerGetProbe(
        client,
        GROK_BILLING_URL,
        auth,
        GrokQuotaParser(),
        clock,
        extraHeaders = linkedMapOf(
            "Accept" to "application/json",
            "x-grok-client-mode" to "cli",
            "x-grok-client-version" to GROK_CLIENT_VERSION,
            "X-XAI-Token-Auth" to "xai-grok-cli",
        ),
    )

    override suspend fun probe(): QuotaSnapshot? = inner.probe()
}

internal class GrokQuotaParser : QuotaParse {
    private val slots = QuotaSlots()

    /** The body is protobuf JSON, which leaves out a field at its zero value: read Oct 10, 2026, a week with no use
     *  answered `currentPeriod` and no `creditUsagePercent` at all. So a body that names its period and omits the
     *  percent is 0% used; only a body with neither is no reading. */
    override fun parse(body: JsonObject, now: Long): QuotaSnapshot? {
        val config = body["config"] as? JsonObject ?: return null
        val period = config["currentPeriod"] as? JsonObject
        val used = num(config["creditUsagePercent"]) ?: period?.let { 0.0 } ?: return null
        val monthly = str(period?.get("type"))?.contains("MONTH") == true
        val seconds = if (monthly) THIRTY_DAYS_SECONDS else SEVEN_DAYS_SECONDS
        val window = QuotaWindow(used, iso(str(period?.get("end"))), seconds)
        return slots.snapshot(listOf(window), null, now)
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

    private fun str(el: kotlinx.serialization.json.JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private const val GROK_BILLING_URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
private const val GROK_CLIENT_VERSION = "0.2.93"
private const val THIRTY_DAYS_SECONDS = 30 * 86_400L
