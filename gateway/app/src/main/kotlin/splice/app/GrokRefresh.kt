// NEW: the grok token-refresh HTTP call (POST grant_type=refresh_token to auth.x.ai) that
// GrokAuthProvider injects. Lives in :app so :provider-grok stays HTTP-client-agnostic and
// unit-testable with a fake refreshCall (mirrors CodexRefresh). G7: classify/retry now goes
// through the shared RefreshRetry.kt loop (same shape as kimiRefresh) instead of a single
// attempt collapsing every non-2xx status AND any thrown exception straight to null.
// G15: classifyGrok's terminal branches now carry a RefreshAttempt so a confirmed invalid_grant
// (401/403/explicit invalid_grant body) is distinguishable from any other rejection at the
// GrokAuthProvider boundary — refreshWithRetry itself is untouched (still generic T?); only the T
// it's instantiated with here changed, from GrokRefreshedTokens to RefreshAttempt<...>.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.auth.RefreshAttempt
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.grok.GrokRefreshedTokens

public class GrokRefresh {

    private val retry = RefreshRetry()

    /**
     * Refresh the grok access token. Returns Granted tokens, InvalidGrant when auth is confirmed
     * dead (401/403/invalid_grant), or Denied when a missing access_token / exhausted retries make
     * re-prompt the honest outcome (NOT evidence the refresh token itself is dead).
     */
    public suspend fun refresh(
        tokenUrl: String,
        refreshToken: String,
        client: HttpClient = grokRefreshClient,
    ): RefreshAttempt<GrokRefreshedTokens> = retry.refreshWithRetry(
        call = { postGrokRefresh(client, tokenUrl, refreshToken) },
        classify = ::classifyGrok,
    ) ?: RefreshAttempt.Denied("refresh retries exhausted")

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

    // Keep failed refreshes diagnosable without copying an untrusted vendor response into logs.
    private suspend fun classifyGrok(resp: HttpResponse): RefreshStep<RefreshAttempt<GrokRefreshedTokens>> {
        if (resp.status.isSuccess()) {
            val tokens = parseGrokRefresh(resp.bodyAsText())
            return RefreshStep.Terminal(
                if (tokens == null) {
                    RefreshAttempt.Denied("refresh response missing access_token")
                } else {
                    RefreshAttempt.Granted(tokens)
                },
            )
        }
        val status = resp.status.value
        val body = resp.bodyAsText()
        System.err.println("[grok] token refresh failed: HTTP $status")
        return when {
            retry.isTerminalRefreshFailure(status, body, grokRefreshJson) ->
                RefreshStep.Terminal(RefreshAttempt.InvalidGrant("HTTP $status"))
            status in refreshRetryableStatus -> RefreshStep.Retry
            else -> RefreshStep.Terminal(RefreshAttempt.Denied("HTTP $status"))
        }
    }

    /** Defensive against malformed JSON on a 200 — a bad success body terminates cleanly, not via retry. */
    private fun parseGrokRefresh(body: String): GrokRefreshedTokens? = Cancellables.runCatchingCancellable {
        val obj = grokRefreshJson.parseToJsonElement(body).jsonObject
        val access = JsonScalars.str(obj, "access_token") ?: return@runCatchingCancellable null
        GrokRefreshedTokens(
            accessToken = access,
            refreshToken = JsonScalars.str(obj, "refresh_token"),
            expiresIn = JsonScalars.long(obj, "expires_in"),
        )
    }.getOrNull()
}

// FILE SCOPE ON PURPOSE (Kotlin style law relocation, 2026-08-15): the lazy client is the default
// argument of [GrokRefresh.refresh], so it must stay a per-JVM singleton. Held as a member it would
// become per-instance, and a caller that constructs GrokRefresh per refresh would open a new JDK
// HttpClient every time.
private val grokRefreshClient by lazy { AuthHttpClientFactory().create() }
private val grokRefreshJson = Json { ignoreUnknownKeys = true }
