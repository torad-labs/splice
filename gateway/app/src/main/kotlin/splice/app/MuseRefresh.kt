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
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val museRefreshClient: HttpClient by lazy { AuthHttpClientFactory().create() }
private const val MAX_MINT_RETRY_AFTER_MS = 3_600_000L
private const val MINT_MS_PER_SECOND = 1_000L

/** App-owned HTTP boundary, separate from provider-owned credential state and persistence. */
public class MuseRefresh(private val clock: WallClock = WallClock(System::currentTimeMillis)) : MuseKeyMintCall {
    private val oauth = MuseOAuth()

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
                MuseMintAttempt.RateLimited(retryAfterMs(response.headers["Retry-After"]))
            response.status == HttpStatusCode.Unauthorized -> MuseMintAttempt.InvalidAccountToken
            response.status == HttpStatusCode.Forbidden && oauth.isAuthFailureBody(body) ->
                MuseMintAttempt.InvalidAccountToken
            response.status.isSuccess() -> oauth.parseMuseKeyResponse(body)
            else -> MuseMintAttempt.Denied("key mint failed (HTTP ${response.status.value})")
        }
    }

    /** Both Retry-After forms, bounded before arithmetic; malformed advice uses the provider's default hold. */
    private fun retryAfterMs(header: String?): Long? {
        val value = header?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (value.all { it in '0'..'9' }) {
            val seconds = value.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: Long.MAX_VALUE
            return seconds.coerceAtMost(MAX_MINT_RETRY_AFTER_MS / MINT_MS_PER_SECOND) * MINT_MS_PER_SECOND
        }
        return try {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val now = Instant.ofEpochMilli(clock())
            Duration.between(now, at.coerceIn(now, now.plusMillis(MAX_MINT_RETRY_AFTER_MS))).toMillis()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
