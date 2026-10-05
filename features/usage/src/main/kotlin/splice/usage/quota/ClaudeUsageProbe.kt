// NEW: the Claude half of failover within one provider (operator ruling, Oct 4, 12:00 AM CT: the console probes
// quota on every page open with no turn, one probe per account). Anthropic's own subscription usage endpoint, the
// one Claude Code's /usage reads:
//
//   GET https://api.anthropic.com/api/oauth/usage
//   Authorization: Bearer <the account's own access token>
//   anthropic-beta: oauth-2025-04-20
//   User-Agent: <the Claude Code User-Agent this daemon's client sends>
//
//   {"five_hour":{"utilization":33.0,"resets_at":"2026-04-11T07:00:00.528743+00:00"},
//    "seven_day":{"utilization":13.0,"resets_at":"2026-04-17T00:59:59.951713+00:00"},
//    "seven_day_opus":null,"seven_day_sonnet":{...},"extra_usage":{...}}
//
// `utilization` is a percentage of the window, 0 to 100. `resets_at` is RFC 3339 and may be absent or null. The
// windows are the same two the unified response headers carry, so they land in the same two slots by LENGTH.
//
// THE SCOPE, verified before this was built: the token must carry `user:profile`. A `claude setup-token` is scoped
// `user:inference` only and Anthropic answers it here with 403 "OAuth token does not meet scope requirement
// user:profile" (anthropics/claude-code#81015), which is why an added account is a full login in its own folder
// (ClaudeAccountFolders) rather than a setup-token.
//
// THE USER-AGENT IS NOT COSMETIC: Anthropic buckets this endpoint's rate limit by User-Agent, and a caller that
// sends none gets a far stricter bucket that answers 429 and stays there for hours with no Retry-After
// (anthropics/claude-code#30930 and #31637). So the probe sends the User-Agent splice has SEEN this daemon's
// Claude Code send, never one it makes up: a head that has seen no client sends none and reports what comes back.
//
// The response shape is from three independent readers of the same endpoint rather than from a live call with
// anyone's credential: anthropics/claude-code#30930 (a captured 200), pleaseai/shunt src/auth/claude/usage.rs, and
// FullFran/claudeops-tui docs/oauth-usage-endpoint.md, all agreeing on the fields above.
package splice.usage.quota

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.auth.AuthProvider
import splice.core.usage.FIVE_HOURS_SECONDS
import splice.core.usage.QuotaSlots
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.usage.SEVEN_DAYS_SECONDS
import splice.core.util.WallClock
import java.time.DateTimeException
import java.time.OffsetDateTime

/** The endpoint is Anthropic's own and takes no base URL from the head: a Claude head's baseUrl can be a proxy or a
 *  gateway, and this account's PLAN usage is only ever Anthropic's to report. */
private const val CLAUDE_USAGE_URL = "https://api.anthropic.com/api/oauth/usage"

// why: the OAuth beta the endpoint requires, the same value every Claude turn already carries.
private const val CLAUDE_OAUTH_BETA = "oauth-2025-04-20"

// why: the body's own field names for the two windows splice has slots for. The per-model weekly windows
// (seven_day_opus, seven_day_sonnet) are read by nothing: Claude Code's own status line draws exactly two bars and
// a model-scoped window has nowhere to go without inventing a third slot.
private const val USAGE_FIVE_HOUR = "five_hour"
private const val USAGE_SEVEN_DAY = "seven_day"

// why: a half-second bias rounds fractional endpoint resets instead of flooring them to the prior minute.
private const val RESET_ROUNDING_HALF_SECOND_NANOS = 500_000_000L

/** The Claude Code User-Agent this daemon has seen its client send, read at probe time rather than at head start:
 *  a head is assembled before any client connects, so a value captured at construction would be null forever. */
public fun interface ClientUserAgent {
    public fun latest(): String?
}

/** One account's plan usage, straight from Anthropic. Its own GET rather than [BearerGetProbe]'s, because one of
 *  its headers changes between calls. */
internal class ClaudeUsageProbe(
    private val client: HttpClient,
    private val auth: AuthProvider,
    private val userAgent: ClientUserAgent,
    private val clock: WallClock,
    private val parse: QuotaParse = ClaudeUsageParser(),
) : QuotaProbe {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun probe(): QuotaSnapshot? {
        // A forwarded credential is the caller's own Claude Code sign-in: splice holds no token for it, so it asks
        // Anthropic nothing on that account's behalf and the console keeps the last snapshot a turn reported.
        val creds = auth.credentials() ?: return null
        val authHeaders = QuotaCredentialHeaders.of(creds) ?: return null
        val response = client.get(CLAUDE_USAGE_URL) {
            authHeaders.forEach { (name, value) -> header(name, value) }
            header("anthropic-beta", CLAUDE_OAUTH_BETA)
            header("Accept", "application/json")
            userAgent.latest()?.let { header("User-Agent", it) }
        }
        // V4-296: a refusal is a failure, so QuotaPoller's log-once path names it; a null here would read as
        // "nothing to record" and freeze the bars on the last snapshot with no line.
        if (response.status != HttpStatusCode.OK) throw QuotaEndpointRefused(response.status.value)
        return parse.parse(json.parseToJsonElement(response.bodyAsText()).jsonObject, clock())
    }
}

internal class ClaudeUsageParser : QuotaParse {
    private val slots = QuotaSlots()

    /** The endpoint names no plan, so the snapshot carries none: the plan word on an added account's card comes
     *  from its own credential (`subscriptionType`), never from a usage reading. */
    override fun parse(body: JsonObject, now: Long): QuotaSnapshot? {
        val windows = listOfNotNull(
            window(body[USAGE_FIVE_HOUR], FIVE_HOURS_SECONDS),
            window(body[USAGE_SEVEN_DAY], SEVEN_DAYS_SECONDS),
        )
        return if (windows.isEmpty()) null else slots.snapshot(windows, null, now)
    }

    /** One `{utilization, resets_at}` object. Null for a window the body omits or reports as null, so an absent
     *  window reads as absent rather than as a bar at zero. */
    private fun window(element: JsonElement?, seconds: Long): QuotaWindow? {
        val reported = (element as? JsonObject) ?: return null
        val used = number(reported["utilization"]) ?: return null
        return QuotaWindow(used, instant(reported["resets_at"]), seconds)
    }

    private fun number(element: JsonElement?): Double? =
        (element as? JsonPrimitive)?.takeIf { it.isString.not() }?.content?.toDoubleOrNull()

    /** RFC 3339 with an offset and fractional seconds, as the endpoint writes it. An instant splice cannot read is
     *  no reset, which the window still reports: a utilization with no instant is worth showing. */
    private fun instant(element: JsonElement?): Long? {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return try {
            OffsetDateTime.parse(text).plusNanos(RESET_ROUNDING_HALF_SECOND_NANOS).toEpochSecond()
        } catch (_: DateTimeException) {
            null
        }
    }
}
