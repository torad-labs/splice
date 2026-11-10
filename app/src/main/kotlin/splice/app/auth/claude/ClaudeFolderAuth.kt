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
// The renewal is the shared TokenRenewal (audit, Nov 9, 2026). This copy had drifted three ways: a failed rotation
// inside the window answered "no credential" while the token still had minutes to live, a rotated pair whose write
// failed was lost though Anthropic had already spent the old refresh token, and the 401 path answered with the very
// token that drew the 401 because it only rotated inside the window. Now a token that is still alive is served
// whatever the rotation said, a grant whose write fails is held and written again, and a 401 always rotates unless
// the folder already holds a newer token than the one served.
package splice.app.auth.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.auth.ACCOUNT_REF_FIELD
import splice.core.auth.AccountRef
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.credentials.HeldToken
import splice.upstream.credentials.RenewalStage
import splice.upstream.credentials.RenewalWindows
import splice.upstream.credentials.TokenRenewal
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
internal data class ClaudeRefreshedTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long) {
    /** Both tokens are secrets; the expiry is not. */
    override fun toString(): String =
        "ClaudeRefreshedTokens(accessToken=<redacted>, refreshToken=<redacted>, expiresAtMs=$expiresAtMs)"
}

/** The POST that rotates one refresh token, injected so the URL and the client live outside this provider and a test
 *  never reaches the network. It answers the endpoint's classified verdict, never a bare null. */
internal fun interface ClaudeTokenRefresh {
    suspend fun rotate(refreshToken: String): RefreshAttempt<ClaudeRefreshedTokens>
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

    // No background scope: inside the window a lookup waits for the rotation, and a token still alive is served
    // whatever it said.
    private val windows = RenewalWindows(CLAUDE_REFRESH_WINDOW_MS, CLAUDE_REFRESH_WINDOW_MS)
    private val renewal: TokenRenewal = TokenRenewal(
        folder.resolve(CREDENTIALS_JSON),
        CLAUDE_ACCOUNT_AUTH_KIND,
        clock,
        log,
        null,
        RenewalStage { prior -> renew(prior) },
    )

    override suspend fun answer(): LoginAnswer =
        renewal.answer(read()?.let { HeldToken(Credentials.Bearer(it.accessToken), it.expiresAtMs) }, windows)

    /** The folder's access token, rotated first when it is at or inside its proactive window. */
    override suspend fun credentials(): Credentials? = (answer() as? LoginAnswer.Granted)?.credentials

    /** What the transport's 401 path calls: one rotation, coalesced with any already in flight. */
    override suspend fun refresh(): Credentials? = (exchange() as? RefreshOutcome.Refreshed)?.credentials

    override suspend fun exchange(): RefreshOutcome = renewal.exchange()

    override suspend fun describe(): AuthDescription {
        val held = read()
        val fields = mutableMapOf("auth_path" to folder.resolve(CREDENTIALS_JSON).toString())
        // A name a person gave beats the email verified from this credential profile.
        val given = ClaudeLoginFactsReader().displayName(folder.resolve(".splice-account.json"))
        given?.let { fields["display_name"] = it }
        held?.let { credential ->
            val key = keyOf(credential.accessToken)
            val account = key?.let { profiles?.read(it) }
            if (account == null && key != null) identities?.request(key, credential.accessToken)
            account?.let {
                fields[ACCOUNT_REF_FIELD] = AccountRef.of(it.uuid)
                it.email?.let { email -> fields["email"] = email }
            }
        }
        return AuthDescription(present = held != null, kind = CLAUDE_ACCOUNT_AUTH_KIND, fields = fields)
    }

    /** The vendor half of one rotation, under the credential lock. The folder is read again first: when it already
     *  holds a token other than [prior], the one this process served, a rotation landed since and is adopted. */
    private suspend fun renew(prior: String?): RefreshOutcome {
        val held = readHeld().getOrElse { return RefreshOutcome.ReadFailed(it) } ?: return RefreshOutcome.NoRefreshToken
        val adopted = prior != null && held.accessToken != prior
        return if (adopted) RefreshOutcome.Refreshed(Credentials.Bearer(held.accessToken)) else rotated(held)
    }

    /** POSTs [held]'s refresh token and keeps what the endpoint granted. */
    private suspend fun rotated(held: Held): RefreshOutcome {
        return when (val attempt = refresh.rotate(held.refreshToken)) {
            is RefreshAttempt.Granted -> {
                val rotated = attempt.tokens
                renewal.persist(document(held.document, rotated))
                carry(held.accessToken, rotated.accessToken)
                RefreshOutcome.Refreshed(Credentials.Bearer(rotated.accessToken))
            }
            is RefreshAttempt.InvalidGrant -> RefreshOutcome.Rejected(INVALID_GRANT_REASON)
            is RefreshAttempt.Denied -> RefreshOutcome.Rejected(attempt.detail)
            is RefreshAttempt.Unavailable -> RefreshOutcome.Unavailable(attempt.detail)
        }
    }

    /** A renewal keeps the account: Anthropic issued [renewed] for this login's own refresh token, so the account
     *  proved for [old] is the renewed token's too, filed under it at once, and the card keeps its email through
     *  the renewal (Nov 3, 2026: five cards went up to four hours without one, until a page read proved the new
     *  token). Then [renewed] is proved in the background, not when a page next reads this login, so the card shows
     *  what the account's profile says now. The record is filed first, so that read can only replace it. */
    private fun carry(old: String, renewed: String) {
        val key = keyOf(renewed) ?: return
        keyOf(old)?.let { profiles?.read(it) }?.let { profiles?.observed(key, it) }
        identities?.reprove(key, renewed)
    }

    /** The identity store's key for [accessToken]: the digest the token is sent under. */
    private fun keyOf(accessToken: String): String? =
        CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $accessToken"))

    /** The whole document plus the three fields splice reads, or null when the folder holds no readable credential. */
    private fun read(): Held? = readHeld()
        .onFailure { why -> log("[claude-account] ${name()} has no readable credential (${why::class.simpleName})\n") }
        .getOrNull()

    /** [folder]'s credential as the three fields splice reads, plus the whole document so a save keeps every other
     *  field Claude Code wrote. Read through the renewal, which serves a rotated document whose write failed until it
     *  is written. A failure is the caller's to report; null inside a success is "nothing filed". */
    private fun readHeld(): Result<Held?> = Cancellables.runCatchingCancellable {
        val document = json.parseToJsonElement(renewal.text.read()).jsonObject
        val oauth = document[OAUTH_FIELD]?.jsonObject ?: return@runCatchingCancellable null
        val access = text(oauth, ACCESS_TOKEN) ?: return@runCatchingCancellable null
        val refreshToken = text(oauth, REFRESH_TOKEN) ?: return@runCatchingCancellable null
        val expiresAt = JsonScalars.long(oauth, EXPIRES_AT) ?: return@runCatchingCancellable null
        Held(access, refreshToken, expiresAt, document)
    }

    /** The document with the rotated pair in place, keeping every other field Claude Code put in it. The renewal
     *  writes it owner-only and atomic, so a reader never sees half a credential. */
    private fun document(document: JsonObject, rotated: ClaudeRefreshedTokens): String {
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
        return json.encodeToString(JsonObject.serializer(), next)
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
    ) {
        /** Both tokens and the document that carries them are secrets; the expiry is not. */
        override fun toString(): String = "Held(<redacted>, expiresAtMs=$expiresAtMs)"
    }
}

private val ROTATED: Set<String> = setOf(ACCESS_TOKEN, REFRESH_TOKEN, EXPIRES_AT)
