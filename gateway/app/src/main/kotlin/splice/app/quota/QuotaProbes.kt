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
package splice.app.quota

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.app.provider.ProviderBuild
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.usage.QuotaSnapshot
import splice.core.util.WallClock
import java.net.URI

internal fun interface QuotaProbe {
    suspend fun probe(): QuotaSnapshot?
}

internal class QuotaProbes(
    private val client: HttpClient,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val parsers = QuotaParsers()

    fun forHead(ctx: ProviderBuild, auth: AuthProvider): QuotaProbe? {
        val base = ctx.providerCfg.baseUrl
        return when (ctx.providerCfg.auth.kind) {
            "chatgpt-oauth" -> probe(codexUsageUrl(base), auth) { obj, now -> parsers.codex(obj, now) }
            "kimi-oauth" -> probe(base.trimEnd('/') + "/v1/usages", auth) { obj, now -> parsers.kimi(obj, now) }
            "grok-oauth" -> probe(GROK_BILLING_URL, auth) { obj, now -> parsers.grok(obj, now) }
            else -> null
        }
    }

    private fun probe(url: String, auth: AuthProvider, parse: QuotaParse): QuotaProbe =
        BearerGetProbe(client, url, auth, parse, clock)

    /** `https://chatgpt.com/backend-api/codex` -> `https://chatgpt.com/backend-api/wham/usage`; the
     *  same shape on a mock origin, which is how the fresh-machine e2e serves it. */
    private fun codexUsageUrl(base: String): String = URI(base).resolve("/backend-api/wham/usage").toString()
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
) : QuotaProbe {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun probe(): QuotaSnapshot? {
        val creds = auth.credentials() as? Credentials.Bearer ?: return null
        val resp = client.get(url) {
            header("Authorization", "Bearer ${creds.token}")
            header("Accept", "application/json")
            creds.accountId?.let { header("ChatGPT-Account-Id", it) }
            header("x-grok-client-mode", "cli")
            header("x-grok-client-version", GROK_CLIENT_VERSION)
            header("X-XAI-Token-Auth", "xai-grok-cli")
        }
        if (resp.status.value != HTTP_OK) return null
        return parse.parse(json.parseToJsonElement(resp.bodyAsText()).jsonObject, clock())
    }
}

private const val HTTP_OK = 200
private const val GROK_BILLING_URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
private const val GROK_CLIENT_VERSION = "0.2.93"
