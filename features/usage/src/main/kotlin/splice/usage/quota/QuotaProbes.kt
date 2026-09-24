// NEW: the provider usage endpoints that answer "how much of my plan is used", one probe per auth
// kind, all read-only GETs on the head's own credential. Verified live 2026-09-02 against each:
//   chatgpt-oauth  GET <origin>/backend-api/wham/usage   rate_limit.{primary,secondary}_window
//                  {used_percent, limit_window_seconds, reset_at|reset_after_seconds}, plan_type.
//                  A Pro plan reports its WEEKLY window as "primary" — slots go by length.
//   kimi-oauth     GET <base>/v1/usages   usage{limit,remaining,resetTime} is the weekly quota,
//                  limits[]{window{duration,timeUnit},detail{limit,remaining,resetTime}} the
//                  5-hour rate window; user.membership.level is the plan.
//   grok-oauth     GET cli-chat-proxy.grok.com/v1/billing?format=credits   config.currentPeriod
//                  {type,end} + creditUsagePercent: one weekly period, no 5-hour window.
// api-key heads have per-minute x-ratelimit-* families, not plan windows; the client-auth head
// relays Anthropic's own unified headers from its rounds. Both get no probe.
package splice.usage.quota

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.usage.QuotaSnapshot
import splice.core.util.WallClock

public fun interface QuotaProbe {
    public suspend fun probe(): QuotaSnapshot?
}

/** Picks a head's probe by its auth kind. The kind and base URL are the head's provider config's; app
 *  passes them in (LAYOUT-01), so the dispatch reads no composition type. */
public class QuotaProbes(
    private val client: HttpClient,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    public fun forHead(authKind: String, baseUrl: String, auth: AuthProvider, usageFields: UsageFields?): QuotaProbe? =
        when (authKind) {
            "chatgpt-oauth" -> CodexQuotaProbe(client, baseUrl, auth, clock)
            "kimi-oauth" -> KimiQuotaProbe(client, baseUrl, auth, clock)
            "grok-oauth" -> GrokQuotaProbe(client, auth, clock)
            "muse-oauth" -> usageFields?.let { MuseMintProbe(it, MuseQuotaParser(), clock) }
            else -> null
        }
}

/** Parses one usage body into a snapshot; a role-named seam so the three parsers share one GET. */
internal fun interface QuotaParse {
    fun parse(body: kotlinx.serialization.json.JsonObject, now: Long): QuotaSnapshot?
}

internal class BearerGetProbe(
    private val client: HttpClient,
    private val url: String,
    private val auth: AuthProvider,
    private val parse: QuotaParse,
    private val clock: WallClock,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : QuotaProbe {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun probe(): QuotaSnapshot? {
        val creds = auth.credentials() ?: return null
        val authHeaders = credentialHeaders(creds) ?: return null
        val resp = client.get(url) {
            authHeaders.forEach { (name, value) -> header(name, value) }
            extraHeaders.forEach { (name, value) -> header(name, value) }
            if (creds is Credentials.Bearer) {
                creds.accountId?.let { header("ChatGPT-Account-Id", it) }
            }
        }
        return if (resp.status.value != HTTP_OK) {
            null
        } else {
            parse.parse(json.parseToJsonElement(resp.bodyAsText()).jsonObject, clock())
        }
    }

    private fun credentialHeaders(creds: Credentials): Map<String, String>? = when (creds) {
        is Credentials.Bearer -> mapOf("Authorization" to "Bearer ${creds.token}")
        is Credentials.ApiKey -> mapOf(creds.header to "${creds.prefix}${creds.key}")
        Credentials.ClientForwarded -> null
    }
}

private const val HTTP_OK = 200
