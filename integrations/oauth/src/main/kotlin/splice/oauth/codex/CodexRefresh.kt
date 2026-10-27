// NEW: the actual token-refresh HTTP call (POST grant_type=refresh_token) the CodexAuthProvider
// injects. Lives in :app (the wiring layer) so :providers-codex stays HTTP-client-agnostic and
// unit-testable with a fake refreshCall.
// G15: classifyCodex's terminal branches carry a RefreshAttempt so a confirmed invalid_grant
// (401/403/explicit invalid_grant body) is distinguishable from any other rejection at the
// CodexAuthProvider boundary.
// Failure-handling slice 4: one call is one exchange. The loop of three POSTs it ran through RefreshRetry is the turn's
// retry engine's now, which asks again 2 s and then 4 s later on an Unavailable answer (TokenEndpointReply).
package splice.oauth.codex

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
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.oauth.AuthHttpClientFactory
import splice.oauth.TokenEndpointReply
import splice.provider.codex.RefreshedTokens
import splice.provider.codex.auth.CodexOAuthEndpoints

// FILE SCOPE ON PURPOSE (Kotlin style law relocation, 2026-08-15): the lazy client is the default
// argument of [CodexRefresh.refresh], so it must stay a per-JVM singleton. Held as a member it
// would become per-instance, and a caller that constructs CodexRefresh per refresh would open a
// new JDK HttpClient every time.
private val refreshClient by lazy { AuthHttpClientFactory().create() }
private val json = Json { ignoreUnknownKeys = true }

// The daemon log (LAYOUT-01): these lines went to bare stderr, which never reaches /mgmt/logs.
public class CodexRefresh(private val log: LogSink) {

    private val reply = TokenEndpointReply(json)

    /**
     * One exchange of the codex refresh token. Returns Granted tokens, InvalidGrant when auth is confirmed dead
     * (401/403/invalid_grant), Unavailable when the endpoint could not answer now (429/5xx), or Denied when a missing
     * access_token or another status makes re-prompt the honest outcome (NOT evidence the refresh token itself is
     * dead). A send that throws reaches the caller, whose provider reads it as a transport failure.
     */
    public suspend fun refresh(
        tokenUrl: String,
        refreshToken: String,
        client: HttpClient = refreshClient,
    ): RefreshAttempt<RefreshedTokens> = classifyCodex(postCodexRefresh(client, tokenUrl, refreshToken))

    private suspend fun postCodexRefresh(client: HttpClient, tokenUrl: String, refreshToken: String): HttpResponse =
        client.submitForm(
            url = tokenUrl,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", CodexOAuthEndpoints.clientId(System::getenv))
            },
        )

    // Keep failed refreshes diagnosable without copying an untrusted vendor response into logs.
    private suspend fun classifyCodex(resp: HttpResponse): RefreshAttempt<RefreshedTokens> {
        if (resp.status.isSuccess()) {
            return parseCodexRefresh(resp.bodyAsText())?.let { RefreshAttempt.Granted(it) }
                ?: RefreshAttempt.Denied("refresh response missing access_token")
        }
        val status = resp.status.value
        val body = resp.bodyAsText()
        log("[codex] token refresh failed: HTTP $status\n")
        return reply.failed(status, body)
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
    }.onFailure { log("[codex] refresh body did not parse: ${SafeFailureText.render(it)}\n") }
        .getOrNull()
}
