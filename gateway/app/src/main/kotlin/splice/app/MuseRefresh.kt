// NEW: Muse's one-shot subscription-key HTTP seam; account tokens are not inference keys.
// No retries or redirects: a hard mint failure returns to the existing auth-exclusion machinery.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import splice.core.GATEWAY_VERSION
import splice.core.util.WallClock
import splice.provider.muse.MuseKeyMintCall
import splice.provider.muse.MuseMintAttempt
import splice.provider.muse.MuseMintMode
import splice.provider.muse.MuseOAuth
import splice.provider.muse.MuseOAuthEndpoints
import splice.upstream.retry.RetryAfter

private val museRefreshClient: HttpClient by lazy { AuthHttpClientFactory().create() }
private const val MAX_MINT_RETRY_AFTER_MS = 3_600_000L

/** App-owned HTTP boundary, separate from provider-owned credential state and persistence. */
public class MuseRefresh(private val clock: WallClock = WallClock(System::currentTimeMillis)) : MuseKeyMintCall {
    private val oauth = MuseOAuth()

    // V4-100: the ONE Retry-After parser is splice.upstream.retry.RetryAfter. This file used to carry its own
    // copy of both RFC 7231 forms (`retryAfterMs`, deleted here) — the mirror nf_04's widened wall
    // now refuses, and the reason it mattered: only THIS copy bounded the value to the mint's hour,
    // so a second parser was a second set of ordering and clamping rules for the same header.
    private val retryAfter = RetryAfter()

    override suspend fun invoke(accessToken: String, mode: MuseMintMode): MuseMintAttempt =
        refresh(MuseOAuthEndpoints.KEY_URL, accessToken, mode)

    public suspend fun refresh(
        keyUrl: String,
        accessToken: String,
        mode: MuseMintMode,
        client: HttpClient = museRefreshClient,
    ): MuseMintAttempt = client.config { followRedirects = false }.use { noRedirectClient ->
        val response = noRedirectClient.post(keyUrl) {
            bearerAuth(accessToken)
            header("User-Agent", "splice/$GATEWAY_VERSION")
            accept(ContentType.Application.Json)
            val body = if (mode == MuseMintMode.ONBOARD) """{"onboard":true}""" else "{}"
            setBody(TextContent(body, ContentType.Application.Json))
        }
        val body = response.bodyAsText()
        when {
            response.status == HttpStatusCode.TooManyRequests ->
                MuseMintAttempt.RateLimited(
                    retryAfter.retryAfterMs(response.headers["Retry-After"], clock)
                        ?.coerceAtMost(MAX_MINT_RETRY_AFTER_MS),
                )
            response.status == HttpStatusCode.Unauthorized -> MuseMintAttempt.InvalidAccountToken
            response.status == HttpStatusCode.Forbidden && oauth.isAuthFailureBody(body) ->
                MuseMintAttempt.InvalidAccountToken
            response.status.isSuccess() -> oauth.parseMuseKeyResponse(body)
            else -> MuseMintAttempt.Denied("key mint failed (HTTP ${response.status.value})")
        }
    }
}
