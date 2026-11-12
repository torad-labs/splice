// NEW: the Claude half of failover within one provider. The one HTTP call that rotates an added Claude account's
// refresh token, so [ClaudeFolderAuth] holds no URL and no client of its own.
//
// It is Claude Code's own request, read from the installed client 2.1.289: POST JSON
// {grant_type: "refresh_token", refresh_token, client_id, scope} to platform.claude.com/v1/oauth/token, answered with
// {access_token, refresh_token, expires_in, scope}. The client id and the scopes are that client's: these folders ARE
// Claude Code logins, made by its own `auth login`, so splice rotates them the way it would.
//
// Only these folders are rotated here. The caller's own sign-in is refreshed by the Claude Code that owns it, which is
// why splice never calls this for it: both rotating one single-use refresh token revokes the other.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.RefreshAttempt
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.oauth.AuthHttpClientFactory
import kotlin.time.Duration.Companion.seconds

// why: Claude Code's own token endpoint and OAuth client, read from the installed 2.1.289 binary's config block.
// A login made by that client can only be rotated by that client's id. Named for Claude rather than TOKEN_URL and
// CLIENT_ID, which already mean another provider's endpoint and another provider's app elsewhere in the tree.
private const val CLAUDE_TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
private const val CLAUDE_OAUTH_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

// why: what the rotated token must still be able to do: send turns, and answer the usage endpoint the console reads.
private const val CLAUDE_OAUTH_SCOPE = "user:inference user:profile"

/** Where this refresh gets its HTTP client: the auth factory in production, a stub in a test. Named for the role,
 *  not the shape — it is the one client an auth POST may use (never ktor CIO, see AuthHttpClientFactory). */
internal fun interface AuthClient {
    fun open(): HttpClient
}

internal class ClaudeOAuthRefresh(
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val client: AuthClient = AuthClient(AuthHttpClientFactory()::create),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Rotates [refreshToken] and classifies the answer (audit, Nov 9, 2026: every failure was one null, so a busy
     *  endpoint read like a dead login): a pair is granted; a 401, a 403 or an `invalid_grant` 400 is a dead refresh
     *  token; a 429, a 5xx or a request that never reached the endpoint is no verdict, asked again later; anything
     *  else is a refusal that says nothing about the token. Neither token is ever logged. */
    suspend fun rotate(refreshToken: String): RefreshAttempt<ClaudeRefreshedTokens> {
        val answered = Cancellables.runCatchingBestEffort { post(refreshToken) }
        return answered.getOrElse { failure ->
            log("[claude-account] token refresh could not be made (${failure::class.simpleName})\n")
            RefreshAttempt.Unavailable("token refresh could not be made (${failure::class.simpleName})")
        }
    }

    private suspend fun post(refreshToken: String): RefreshAttempt<ClaudeRefreshedTokens> = client.open().use { http ->
        val response = http.post(CLAUDE_TOKEN_URL) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            setBody(body(refreshToken).toString())
        }
        if (response.status == HttpStatusCode.OK) return@use tokens(response.bodyAsText())
        log("[claude-account] token refresh refused with ${response.status.value}\n")
        refused(response.status.value, response.bodyAsText())
    }

    /** What a refusal says about the refresh token: dead, no verdict, or a refusal that names neither. The body is read
     *  only for the invalid_grant code and never repeated. */
    private fun refused(status: Int, body: String): RefreshAttempt<Nothing> {
        val detail = "token refresh refused with $status"
        val dead = status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN ||
            (status == HttpStatus.BAD_REQUEST && INVALID_GRANT_REASON in body)
        return when {
            dead -> RefreshAttempt.InvalidGrant(detail)
            status == HttpStatus.TOO_MANY_REQUESTS || status >= HttpStatus.INTERNAL_SERVER_ERROR ->
                RefreshAttempt.Unavailable(detail)
            else -> RefreshAttempt.Denied(detail)
        }
    }

    private fun body(refreshToken: String): JsonObject = buildJsonObject {
        put("grant_type", "refresh_token")
        put("refresh_token", refreshToken)
        put("client_id", CLAUDE_OAUTH_CLIENT_ID)
        put("scope", CLAUDE_OAUTH_SCOPE)
    }

    /** The rotated pair, or a refusal when the answer does not carry one: an answer splice cannot read is a refusal,
     *  never a credential it half-believes. */
    private fun tokens(text: String): RefreshAttempt<ClaudeRefreshedTokens> {
        val why = try {
            rotated(json.parseToJsonElement(text).jsonObject)?.let { return RefreshAttempt.Granted(it) }
            "token refresh answered without a usable credential"
        } catch (_: SerializationException) {
            "token refresh answered with something that is not JSON"
        } catch (_: IllegalArgumentException) {
            "token refresh answered with an unreadable credential"
        }
        log("[claude-account] $why\n")
        return RefreshAttempt.Denied(why)
    }

    /** All three fields or nothing: a rotation missing any of them is not a credential. */
    private fun rotated(fields: JsonObject): ClaudeRefreshedTokens? = text(fields, "access_token")?.let { access ->
        text(fields, "refresh_token")?.let { refreshed ->
            text(fields, "expires_in")?.toLongOrNull()?.let { expiresIn ->
                ClaudeRefreshedTokens(access, refreshed, clock() + expiresIn.seconds.inWholeMilliseconds)
            }
        }
    }

    private fun text(fields: JsonObject, key: String): String? =
        JsonScalars.str(fields, key)?.takeIf { it.isNotBlank() }
}
