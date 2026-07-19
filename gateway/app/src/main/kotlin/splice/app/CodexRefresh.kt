// NEW: the actual token-refresh HTTP call (POST grant_type=refresh_token) the CodexAuthProvider
// injects. Lives in :app (the wiring layer) so :provider-codex stays HTTP-client-agnostic and
// unit-testable with a fake refreshCall. G7: classify/retry now goes through the shared
// RefreshRetry.kt loop (same shape as kimiRefresh) instead of a single attempt collapsing every
// non-2xx status AND any thrown exception straight to null.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import splice.provider.codex.CodexOAuthEndpoints
import splice.provider.codex.RefreshedTokens

private val refreshClient by lazy { authHttpClient() }
private val json = Json { ignoreUnknownKeys = true }

public suspend fun codexRefresh(
    tokenUrl: String,
    refreshToken: String,
    client: HttpClient = refreshClient,
): RefreshedTokens? = refreshWithRetry(
    call = { postCodexRefresh(client, tokenUrl, refreshToken) },
    classify = ::classifyCodex,
)

private suspend fun postCodexRefresh(client: HttpClient, tokenUrl: String, refreshToken: String): HttpResponse =
    client.submitForm(
        url = tokenUrl,
        formParameters = Parameters.build {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", CodexOAuthEndpoints.clientId(System::getenv))
        },
    )

// refresh failure -> null (caller re-prompts), with the CAUSE on stderr — a silent null left
// the operator staring at persistent 401s with zero evidence (audit 2026-07-18). Logged per
// attempt now that a transient failure retries instead of terminating immediately.
private suspend fun classifyCodex(resp: HttpResponse): RefreshStep<RefreshedTokens> {
    if (resp.status.isSuccess()) return RefreshStep.Terminal(parseCodexRefresh(resp.bodyAsText()))
    val status = resp.status.value
    val body = resp.bodyAsText()
    System.err.println("[codex] token refresh failed: HTTP $status ${body.take(ERR_BODY_SNIPPET)}")
    return when {
        isTerminalRefreshFailure(status, body, json) -> RefreshStep.Terminal(null)
        status in refreshRetryableStatus -> RefreshStep.Retry
        else -> RefreshStep.Terminal(null)
    }
}

/** Defensive against malformed JSON on a 200 — a bad success body terminates cleanly, not via retry. */
private fun parseCodexRefresh(body: String): RefreshedTokens? = runCatchingCancellable {
    val obj = json.parseToJsonElement(body).jsonObject
    val access = obj.str("access_token") ?: return@runCatchingCancellable null
    RefreshedTokens(
        accessToken = access,
        refreshToken = obj.str("refresh_token"),
        idToken = obj.str("id_token"),
    )
}.getOrNull()

private const val ERR_BODY_SNIPPET = 200
