// NEW: the grok token-refresh HTTP call (POST grant_type=refresh_token to auth.x.ai) that
// GrokAuthProvider injects. Lives in :app so :provider-grok stays HTTP-client-agnostic and
// unit-testable with a fake refreshCall (mirrors CodexRefresh). G7: classify/retry now goes
// through the shared RefreshRetry.kt loop (same shape as kimiRefresh) instead of a single
// attempt collapsing every non-2xx status AND any thrown exception straight to null.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.util.long
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.grok.GrokRefreshedTokens

public suspend fun grokRefresh(
    tokenUrl: String,
    refreshToken: String,
    client: HttpClient = grokRefreshClient,
): GrokRefreshedTokens? = refreshWithRetry(
    call = { postGrokRefresh(client, tokenUrl, refreshToken) },
    classify = ::classifyGrok,
)

private suspend fun postGrokRefresh(client: HttpClient, tokenUrl: String, refreshToken: String): HttpResponse =
    client.submitForm(
        url = tokenUrl,
        formParameters = Parameters.build {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            // Same env override the login flow honors — hard-coding the default looped
            // custom-client setups through invalid_grant forever.
            append("client_id", GrokOAuthEndpoints.clientId(System::getenv))
        },
    )

// refresh failure -> null (re-prompt), with the CAUSE on stderr — a silent null left the
// operator staring at persistent 401s with zero evidence (audit 2026-07-18). Logged per attempt
// now that a transient failure retries instead of terminating immediately.
private suspend fun classifyGrok(resp: HttpResponse): RefreshStep<GrokRefreshedTokens> {
    if (resp.status.isSuccess()) return RefreshStep.Terminal(parseGrokRefresh(resp.bodyAsText()))
    val status = resp.status.value
    val body = resp.bodyAsText()
    System.err.println("[grok] token refresh failed: HTTP $status ${body.take(ERR_BODY_SNIPPET)}")
    return when {
        isTerminalRefreshFailure(status, body, grokRefreshJson) -> RefreshStep.Terminal(null)
        status in refreshRetryableStatus -> RefreshStep.Retry
        else -> RefreshStep.Terminal(null)
    }
}

/** Defensive against malformed JSON on a 200 — a bad success body terminates cleanly, not via retry. */
private fun parseGrokRefresh(body: String): GrokRefreshedTokens? = runCatchingCancellable {
    val obj = grokRefreshJson.parseToJsonElement(body).jsonObject
    val access = obj.str("access_token") ?: return@runCatchingCancellable null
    GrokRefreshedTokens(
        accessToken = access,
        refreshToken = obj.str("refresh_token"),
        expiresIn = obj.long("expires_in"),
    )
}.getOrNull()

private val grokRefreshClient by lazy { authHttpClient() }
private val grokRefreshJson = Json { ignoreUnknownKeys = true }

private const val ERR_BODY_SNIPPET = 200
