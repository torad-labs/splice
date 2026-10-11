// NEW: the Claude half of failover within one provider. The credential of ONE account splice added to a Claude head,
// read from the folder splice owns for it (ClaudeAccountFolders) and refreshed by splice alone.
//
// WHY SPLICE MAY REFRESH THIS ONE: Claude Code's refresh tokens are single-use and rotate, so a second user of the
// same credential revokes the first — that is what took a login down on 2026-09-25 (V4-237, V4-250). No Claude Code
// runs against these folders, so splice is the only user and the rotation is safe. The CALLER's own sign-in is the
// login splice never touches: that one rides as ClientAuthProvider's forwarded credential.
//
// The refresh is Claude Code's own: POST JSON {grant_type: refresh_token, refresh_token, client_id, scope} to
// platform.claude.com/v1/oauth/token, read from the installed client 2.1.289. The HTTP call itself is injected
// ([ClaudeTokenRefresh]) so tests never reach the network and the URL lives in one place.
//
// FAIL CLOSED: a refusal leaves the folder exactly as it was and answers "no credential", so the turn ends on the
// upstream's own word instead of a token splice knows is spent.
package splice.app.auth.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaHeaderRead
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.upstream.retry.SingleFlight
import java.nio.file.Files
import java.nio.file.Path

// why: how long before its own expiresAt an added account's token is rotated, so a turn never races the clock.
// Claude Code's own refresh window is 5 minutes, and these folders hold Claude Code's own credentials, so this is
// that client's number rather than a splice policy. Named for Claude, not shared with the codex and grok windows:
// those come from a JWT exp claim and from xAI's expiry, and changing one must never move the others.
private const val CLAUDE_REFRESH_WINDOW_MS = 300_000L

// why: the fields Claude Code writes in .credentials.json. splice rewrites only these three and keeps the rest of the
// document byte for byte, so the folder stays a folder Claude Code itself could read.
private const val OAUTH_FIELD = "claudeAiOauth"
private const val ACCESS_TOKEN = "accessToken"
private const val REFRESH_TOKEN = "refreshToken"
private const val EXPIRES_AT = "expiresAt"

/** The `auth.kind` word an added Claude account describes itself by: not `client`, because splice DOES hold this one. */
internal const val CLAUDE_ACCOUNT_AUTH_KIND = "claude-account"

/** One rotation's answer from Anthropic's token endpoint. */
internal data class ClaudeRefreshedTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

/** The POST that rotates one refresh token, injected so the URL and the client live outside this provider and a test
 *  never reaches the network. Null is any refusal the endpoint gave; the caller then fails closed. */
internal fun interface ClaudeTokenRefresh {
    suspend fun rotate(refreshToken: String): ClaudeRefreshedTokens?
}

internal class ClaudeFolderAuth(
    private val folder: Path,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val refresh: ClaudeTokenRefresh,
    private val log: LogSink = LogSink { },
    private val profiles: ClaudeCredentialProfiles? = null,
    private val identities: ClaudeIdentityRefresh? = null,
) : RefreshableAuthProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val single = SingleFlight<Credentials?>()

    // The unified plan family lives in :core as an internal object, and the one public reader of it is the provider
    // that forwards the caller's Claude login. An added account's 429 carries the same headers, so this delegates to
    // that reader rather than copying the parse. Nothing else of it is used: this instance serves no turn.
    private val planFamily = ClientAuthProvider(folder.fileName.toString())

    /** The folder's access token, rotated first when it is at or inside its proactive window. */
    override suspend fun credentials(): Credentials? {
        val held = read() ?: return null
        if (clock() < held.expiresAtMs - CLAUDE_REFRESH_WINDOW_MS) return Credentials.Bearer(held.accessToken)
        return single.run { rotate() }
    }

    /** What the transport's 401 path calls. The same rotation, coalesced with any already in flight. */
    override suspend fun refresh(): Credentials? = read()?.let { single.run { rotate() } }

    override suspend fun describe(): AuthDescription {
        val held = read()
        val fields = mutableMapOf("auth_path" to folder.resolve(CREDENTIALS_JSON).toString())
        fields["display_name"] = ClaudeLoginFactsReader().displayName(folder.resolve(".splice-account.json")) ?: name()
        held?.let { credential ->
            val key = CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer ${credential.accessToken}"))
            val account = key?.let { profiles?.read(it) }
            if (account == null && key != null) identities?.request(key, credential.accessToken)
            account?.let {
                fields["account_uuid"] = it.uuid
                it.email?.let { email -> fields["account_email"] = email }
            }
        }
        return AuthDescription(present = held != null, kind = CLAUDE_ACCOUNT_AUTH_KIND, fields = fields)
    }

    /** This account is a Claude subscription, answered in Anthropic's unified plan family, so its 429 names the plan
     *  window it spent and the reset it comes back at — the same read ClientAuthProvider makes for the caller's own. */
    override fun planLimit(header: QuotaHeaderRead, nowEpochSeconds: Long): PlanLimit? =
        planFamily.planLimit(header, nowEpochSeconds)

    /** Reads the folder again inside the single flight, so a turn that waited on another turn's rotation sends the
     *  token that rotation saved instead of starting a second one. */
    private suspend fun rotate(): Credentials? {
        val held = read() ?: return null
        if (clock() < held.expiresAtMs - CLAUDE_REFRESH_WINDOW_MS) return Credentials.Bearer(held.accessToken)
        // Null is the endpoint refusing, or the call itself failing: the folder stays exactly as it was.
        return Cancellables.runCatchingCancellable { refresh.rotate(held.refreshToken) }
            .onFailure { why -> log("[claude-account] ${name()} could not be refreshed (${why::class.simpleName})\n") }
            .getOrNull()?.let { rotated ->
                save(held.document, rotated)
                Credentials.Bearer(rotated.accessToken)
            }
    }

    /** The whole document plus the three fields splice reads, or null when the folder holds no readable credential. */
    private fun read(): Held? = readHeld()
        .onFailure { why -> log("[claude-account] ${name()} has no readable credential (${why::class.simpleName})\n") }
        .getOrNull()

    /** [folder]'s credential as the three fields splice reads, plus the whole document so a save keeps every other
     *  field Claude Code wrote. A failure is the caller's to report; null inside a success is "nothing filed". */
    private fun readHeld(): Result<Held?> = Cancellables.runCatchingCancellable {
        val document = json.parseToJsonElement(Files.readString(folder.resolve(CREDENTIALS_JSON))).jsonObject
        val oauth = document[OAUTH_FIELD]?.jsonObject ?: return@runCatchingCancellable null
        val access = text(oauth, ACCESS_TOKEN) ?: return@runCatchingCancellable null
        val refreshToken = text(oauth, REFRESH_TOKEN) ?: return@runCatchingCancellable null
        val expiresAt = JsonScalars.long(oauth, EXPIRES_AT) ?: return@runCatchingCancellable null
        Held(access, refreshToken, expiresAt, document)
    }

    /** Writes the rotated pair back, keeping every other field Claude Code put in the document. Owner-only and
     *  atomic, so a reader never sees half a credential. */
    private fun save(document: JsonObject, rotated: ClaudeRefreshedTokens) {
        val oauth = document[OAUTH_FIELD]?.jsonObject ?: JsonObject(emptyMap())
        val next = buildJsonObject {
            document.forEach { (key, value) -> if (key != OAUTH_FIELD) put(key, value) }
            put(
                OAUTH_FIELD,
                buildJsonObject {
                    oauth.forEach { (key, value) -> if (key !in ROTATED) put(key, value) }
                    put(ACCESS_TOKEN, rotated.accessToken)
                    put(REFRESH_TOKEN, rotated.refreshToken)
                    put(EXPIRES_AT, rotated.expiresAtMs)
                },
            )
        }
        SecureFile.writeAtomic0600(folder.resolve(CREDENTIALS_JSON), json.encodeToString(JsonObject.serializer(), next))
    }

    private fun text(obj: JsonObject, key: String): String? =
        JsonScalars.strIfString(obj[key]).takeIf { it.isNotBlank() }

    /** The account's label, which is its folder's own name: enough to act on, and never a token. */
    private fun name(): String = folder.fileName?.toString() ?: folder.toString()

    private data class Held(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
        val document: JsonObject,
    )
}

private val ROTATED: Set<String> = setOf(ACCESS_TOKEN, REFRESH_TOKEN, EXPIRES_AT)
