// NEW: the actual token-refresh HTTP call (POST grant_type=refresh_token) the CodexAuthProvider
// injects. Lives in :app (the wiring layer) so :provider-codex stays HTTP-client-agnostic and
// unit-testable with a fake refreshCall. G7: classify/retry now goes through the shared
// RefreshRetry.kt loop (same shape as kimiRefresh) instead of a single attempt collapsing every
// non-2xx status AND any thrown exception straight to null.
// G15: classifyCodex's terminal branches now carry a RefreshAttempt so a confirmed invalid_grant
// (401/403/explicit invalid_grant body) is distinguishable from any other rejection at the
// CodexAuthProvider boundary — refreshWithRetry itself is untouched (still generic T?); only the T
// it's instantiated with here changed, from RefreshedTokens to RefreshAttempt<...>.
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
import splice.provider.codex.CodexOAuthEndpoints
import splice.provider.codex.RefreshedTokens

// FILE SCOPE ON PURPOSE (Kotlin style law relocation, 2026-08-15): the lazy client is the default
// argument of [CodexRefresh.refresh], so it must stay a per-JVM singleton. Held as a member it
// would become per-instance, and a caller that constructs CodexRefresh per refresh would open a
// new JDK HttpClient every time.
private val refreshClient by lazy { AuthHttpClientFactory().create() }
private val json = Json { ignoreUnknownKeys = true }

public class CodexRefresh {

    private val retry = RefreshRetry()

    public suspend fun refresh(
        tokenUrl: String,
        refreshToken: String,
        client: HttpClient = refreshClient,
    ): RefreshAttempt<RefreshedTokens> = retry.refreshWithRetry(
        call = { postCodexRefresh(client, tokenUrl, refreshToken) },
        classify = ::classifyCodex,
    ) ?: RefreshAttempt.Denied("refresh retries exhausted")

    private suspend fun postCodexRefresh(client: HttpClient, tokenUrl: String, refreshToken: String): HttpResponse =
        client.submitForm(
            url = tokenUrl,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", CodexOAuthEndpoints.clientId(System::getenv))
            },
        )

    // refresh failure -> Denied/InvalidGrant (caller re-prompts), with the CAUSE on stderr — a silent
    // null left the operator staring at persistent 401s with zero evidence (audit 2026-07-18). Logged
    // per attempt now that a transient failure retries instead of terminating immediately.
    private suspend fun classifyCodex(resp: HttpResponse): RefreshStep<RefreshAttempt<RefreshedTokens>> {
        if (resp.status.isSuccess()) {
            val tokens = parseCodexRefresh(resp.bodyAsText())
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
        System.err.println("[codex] token refresh failed: HTTP $status ${body.take(ERR_BODY_SNIPPET)}")
        return when {
            retry.isTerminalRefreshFailure(status, body, json) ->
                RefreshStep.Terminal(RefreshAttempt.InvalidGrant("HTTP $status"))
            status in refreshRetryableStatus -> RefreshStep.Retry
            else -> RefreshStep.Terminal(RefreshAttempt.Denied("HTTP $status"))
        }
    }

    /** Defensive against malformed JSON on a 200 — a bad success body terminates cleanly, not via retry. */
    private fun parseCodexRefresh(body: String): RefreshedTokens? = Cancellables.runCatchingCancellable {
        val obj = json.parseToJsonElement(body).jsonObject
        val access = JsonScalars.str(obj, "access_token") ?: return@runCatchingCancellable null
        RefreshedTokens(
            accessToken = access,
            refreshToken = JsonScalars.str(obj, "refresh_token"),
            idToken = JsonScalars.str(obj, "id_token"),
        )
    }.getOrNull()
}

private const val ERR_BODY_SNIPPET = 200
