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
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.WallClock
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

    /** Rotates [refreshToken] and returns the new pair, or null on any refusal. Neither token is ever logged. */
    suspend fun rotate(refreshToken: String): ClaudeRefreshedTokens? {
        val answered = Cancellables.runCatchingBestEffort { post(refreshToken) }
        return answered.getOrElse { failure ->
            log("[claude-account] token refresh could not be made (${failure::class.simpleName})\n")
            null
        }
    }

    private suspend fun post(refreshToken: String): ClaudeRefreshedTokens? = client.open().use { http ->
        val response = http.post(CLAUDE_TOKEN_URL) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            setBody(body(refreshToken).toString())
        }
        if (response.status != HttpStatusCode.OK) {
            log("[claude-account] token refresh refused with ${response.status.value}\n")
            return@use null
        }
        tokens(response.bodyAsText())
    }

    private fun body(refreshToken: String): JsonObject = buildJsonObject {
        put("grant_type", "refresh_token")
        put("refresh_token", refreshToken)
        put("client_id", CLAUDE_OAUTH_CLIENT_ID)
        put("scope", CLAUDE_OAUTH_SCOPE)
    }

    /** The rotated pair, or null when the answer does not carry one: an answer splice cannot read is a refusal,
     *  never a credential it half-believes. */
    private fun tokens(text: String): ClaudeRefreshedTokens? = try {
        rotated(json.parseToJsonElement(text).jsonObject)
            ?: null.also { log("[claude-account] token refresh answered without a usable credential\n") }
    } catch (_: SerializationException) {
        log("[claude-account] token refresh answered with something that is not JSON\n")
        null
    } catch (_: IllegalArgumentException) {
        log("[claude-account] token refresh answered with an unreadable credential\n")
        null
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
