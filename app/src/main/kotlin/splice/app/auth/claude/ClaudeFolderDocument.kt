// NEW: .credentials.json parsing, rewriting and credential-key derivation for ClaudeFolderAuth.
package splice.app.auth.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.auth.CredentialKey
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.upstream.credentials.TokenRenewal
import java.nio.file.Path

// why: the fields Claude Code writes in .credentials.json. splice rewrites only these three and keeps the rest of the
// document byte for byte, so the folder stays a folder Claude Code itself could read.
private const val OAUTH_FIELD = "claudeAiOauth"
private const val ACCESS_TOKEN = "accessToken"
private const val REFRESH_TOKEN = "refreshToken"
private const val EXPIRES_AT = "expiresAt"
private val ROTATED_FIELDS: Set<String> = setOf(ACCESS_TOKEN, REFRESH_TOKEN, EXPIRES_AT)

internal data class ClaudeFolderHeld(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val document: JsonObject,
) {
    /** Both tokens and the document that carries them are secrets; the expiry is not. */
    override fun toString(): String = "ClaudeFolderHeld(<redacted>, expiresAtMs=$expiresAtMs)"
}

internal class ClaudeFolderDocument(
    private val folder: Path,
    private val renewal: TokenRenewal,
    private val log: LogSink,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The identity store's key for [accessToken]: the digest the token is sent under. */
    fun keyOf(accessToken: String): String? =
        CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $accessToken"))

    /** The whole document plus the three fields splice reads, or null when the folder holds no readable credential. */
    fun read(): ClaudeFolderHeld? = readHeld()
        // The account's label is its folder's own name: enough to act on, and never a token.
        .onFailure { why ->
            log("[claude-account] ${folder.fileName ?: folder} has no readable credential (${why::class.simpleName})\n")
        }
        .getOrNull()

    /** [folder]'s credential as the three fields splice reads, plus the whole document so a save keeps every other
     *  field Claude Code wrote. Read through the renewal, which serves a rotated document whose write failed until it
     *  is written. A failure is the caller's to report; null inside a success is "nothing filed". */
    fun readHeld(): Result<ClaudeFolderHeld?> = Cancellables.runCatchingCancellable {
        val document = json.parseToJsonElement(renewal.text.read()).jsonObject
        val oauth = document[OAUTH_FIELD]?.jsonObject ?: return@runCatchingCancellable null
        val access = text(oauth, ACCESS_TOKEN) ?: return@runCatchingCancellable null
        val refreshToken = text(oauth, REFRESH_TOKEN) ?: return@runCatchingCancellable null
        val expiresAt = JsonScalars.long(oauth, EXPIRES_AT) ?: return@runCatchingCancellable null
        ClaudeFolderHeld(access, refreshToken, expiresAt, document)
    }

    /** The document with the rotated pair in place, keeping every other field Claude Code put in it. The renewal
     *  writes it owner-only and atomic, so a reader never sees half a credential. */
    fun rewrite(document: JsonObject, rotated: ClaudeRefreshedTokens): String {
        val oauth = document[OAUTH_FIELD]?.jsonObject ?: JsonObject(emptyMap())
        val next = buildJsonObject {
            document.forEach { (key, value) -> if (key != OAUTH_FIELD) put(key, value) }
            put(
                OAUTH_FIELD,
                buildJsonObject {
                    oauth.forEach { (key, value) -> if (key !in ROTATED_FIELDS) put(key, value) }
                    put(ACCESS_TOKEN, rotated.accessToken)
                    put(REFRESH_TOKEN, rotated.refreshToken)
                    put(EXPIRES_AT, rotated.expiresAtMs)
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), next)
    }

    private fun text(obj: JsonObject, key: String): String? =
        JsonScalars.strIfString(obj[key]).takeIf { it.isNotBlank() }
}
