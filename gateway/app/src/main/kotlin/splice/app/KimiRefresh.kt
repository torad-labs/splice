// NEW: the Kimi (Moonshot) token-refresh HTTP call — POST grant_type=refresh_token to
// auth.kimi.com's token URL, the network hop KimiAuthProvider injects. Lives in :app so
// :provider-kimi stays HTTP-client-agnostic and unit-testable with a fake refreshCall (mirrors
// GrokRefresh). Invariants: rotation is mandatory (a response missing refresh_token → Denied →
// re-prompt); 401/403 and error=="invalid_grant" are terminal (auth dead → InvalidGrant);
// 429/500/502/503/504 are retryable (3 attempts, backoff 2^attempt seconds, ±10% jitter — see
// RefreshRetry.kt); the X-Msh-* device identity headers ride on the refresh POST too. JsonNull-safe
// extraction throughout.
// G15: classify()'s terminal branches now carry a RefreshAttempt so a confirmed invalid_grant is
// distinguishable from any other rejection at the KimiAuthProvider boundary — refreshWithRetry
// itself is untouched (still generic T?); only the T it's instantiated with here changed.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.auth.RefreshAttempt
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import splice.provider.kimi.KimiRefreshedTokens
import splice.provider.kimi.kimiRefreshForm

/**
 * Refresh the kimi access token. Returns Granted rotated tokens, InvalidGrant when auth is
 * confirmed dead (401/403/invalid_grant), or Denied when a missing rotation field / exhausted
 * retries make re-prompt the honest outcome (NOT evidence the refresh token itself is dead).
 */
public suspend fun kimiRefresh(
    tokenUrl: String,
    refreshToken: String,
    identityHeaders: Map<String, String>,
    client: HttpClient = kimiRefreshClient,
): RefreshAttempt<KimiRefreshedTokens> = refreshWithRetry(
    call = { postRefresh(client, tokenUrl, refreshToken, identityHeaders) },
    classify = ::classify,
) ?: RefreshAttempt.Denied("refresh retries exhausted")

private suspend fun classify(resp: HttpResponse): RefreshStep<RefreshAttempt<KimiRefreshedTokens>> {
    val status = resp.status.value
    val body = resp.bodyAsText()
    return when {
        resp.status.isSuccess() -> {
            val tokens = parseKimiRefresh(body)
            RefreshStep.Terminal(
                if (tokens == null) {
                    RefreshAttempt.Denied("refresh response missing rotation fields")
                } else {
                    RefreshAttempt.Granted(tokens)
                },
            )
        }
        isTerminalRefreshFailure(status, body, kimiRefreshJson) ->
            RefreshStep.Terminal(RefreshAttempt.InvalidGrant("HTTP $status"))
        status in refreshRetryableStatus -> RefreshStep.Retry
        else -> RefreshStep.Terminal(RefreshAttempt.Denied("HTTP $status"))
    }
}

private suspend fun postRefresh(
    client: HttpClient,
    tokenUrl: String,
    refreshToken: String,
    identityHeaders: Map<String, String>,
): HttpResponse = client.post(tokenUrl) {
    header("Content-Type", "application/x-www-form-urlencoded")
    header("Accept", "application/json")
    identityHeaders.forEach { (k, v) -> header(k, v) }
    setBody(kimiRefreshForm(refreshToken))
}

/** Rotation is mandatory: a response missing access_token OR refresh_token → null (re-prompt). */
private fun parseKimiRefresh(body: String): KimiRefreshedTokens? = runCatchingCancellable {
    val obj = kimiRefreshJson.parseToJsonElement(body) as? JsonObject ?: return@runCatchingCancellable null
    val access = obj.str("access_token") ?: return@runCatchingCancellable null
    val refresh = obj.str("refresh_token") ?: return@runCatchingCancellable null
    val expiresIn = obj.str("expires_in")?.toLongOrNull() ?: return@runCatchingCancellable null
    KimiRefreshedTokens(
        accessToken = access,
        refreshToken = refresh,
        expiresIn = expiresIn,
        scope = obj.str("scope").orEmpty(),
        tokenType = obj.str("token_type") ?: "Bearer",
    )
}.getOrNull()

// JsonNull IS a JsonPrimitive whose content is "null"; treat an explicit null as absent.

private val kimiRefreshClient by lazy { authHttpClient() }
private val kimiRefreshJson = Json { ignoreUnknownKeys = true }
