// NEW: the Kimi (Moonshot) token-refresh HTTP call — POST grant_type=refresh_token to
// auth.kimi.com's token URL, the network hop KimiAuthProvider injects. Lives in :app so
// :provider-kimi stays HTTP-client-agnostic and unit-testable with a fake refreshCall (mirrors
// GrokRefresh). Invariants: rotation is mandatory (a response missing refresh_token → null →
// re-prompt); 401/403 and error=="invalid_grant" are terminal (auth dead → null); 429/500/502/503/504
// are retryable (3 attempts, backoff 2^attempt seconds); the X-Msh-* device identity headers ride
// on the refresh POST too. JsonNull-safe extraction throughout.
package splice.app

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import splice.provider.kimi.KimiRefreshedTokens
import splice.provider.kimi.kimiRefreshForm

private const val KIMI_MAX_ATTEMPTS = 3
private const val KIMI_HTTP_UNAUTHORIZED = 401
private const val KIMI_HTTP_FORBIDDEN = 403
private const val KIMI_HTTP_TOO_MANY = 429
private const val KIMI_HTTP_INTERNAL = 500
private const val KIMI_HTTP_BAD_GATEWAY = 502
private const val KIMI_HTTP_UNAVAILABLE = 503
private const val KIMI_HTTP_GATEWAY_TIMEOUT = 504
private const val KIMI_BACKOFF_BASE_MS = 1000L
private val KIMI_RETRYABLE_STATUS = setOf(
    KIMI_HTTP_TOO_MANY,
    KIMI_HTTP_INTERNAL,
    KIMI_HTTP_BAD_GATEWAY,
    KIMI_HTTP_UNAVAILABLE,
    KIMI_HTTP_GATEWAY_TIMEOUT,
)

/** One refresh attempt's verdict: terminal (rotated tokens or dead auth) or worth retrying. */
private sealed class RefreshStep {
    data class Terminal(val tokens: KimiRefreshedTokens?) : RefreshStep()
    data object Retry : RefreshStep()
}

/**
 * Refresh the kimi access token. Returns rotated tokens, or null when auth is dead (401/403/
 * invalid_grant) or a missing rotation field / exhausted retries make re-prompt the honest outcome.
 */
public suspend fun kimiRefresh(
    tokenUrl: String,
    refreshToken: String,
    identityHeaders: Map<String, String>,
): KimiRefreshedTokens? {
    var attempt = 0
    while (attempt < KIMI_MAX_ATTEMPTS) {
        val step = runCatchingCancellable {
            classify(postRefresh(tokenUrl, refreshToken, identityHeaders))
        }.getOrDefault(RefreshStep.Retry)
        if (step is RefreshStep.Terminal) return step.tokens
        attempt++
        if (attempt < KIMI_MAX_ATTEMPTS) delay(KIMI_BACKOFF_BASE_MS shl attempt) // 2^attempt seconds
    }
    return null
}

private suspend fun classify(resp: HttpResponse): RefreshStep {
    val status = resp.status.value
    val body = resp.bodyAsText()
    return when {
        resp.status.isSuccess() -> RefreshStep.Terminal(parseKimiRefresh(body))
        status == KIMI_HTTP_UNAUTHORIZED || status == KIMI_HTTP_FORBIDDEN -> RefreshStep.Terminal(null)
        isInvalidGrant(body) -> RefreshStep.Terminal(null)
        status in KIMI_RETRYABLE_STATUS -> RefreshStep.Retry
        else -> RefreshStep.Terminal(null)
    }
}

private suspend fun postRefresh(
    tokenUrl: String,
    refreshToken: String,
    identityHeaders: Map<String, String>,
): HttpResponse = kimiRefreshClient.post(tokenUrl) {
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

private fun isInvalidGrant(body: String): Boolean = runCatchingCancellable {
    (kimiRefreshJson.parseToJsonElement(body) as? JsonObject)?.str("error") == "invalid_grant"
}.getOrDefault(false)

// JsonNull IS a JsonPrimitive whose content is "null"; treat an explicit null as absent.

private val kimiRefreshClient by lazy { authHttpClient() }
private val kimiRefreshJson = Json { ignoreUnknownKeys = true }
