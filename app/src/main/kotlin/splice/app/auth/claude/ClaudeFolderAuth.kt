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
// the folder already holds a newer token than the one the refused request sent.
package splice.app.auth.claude

import splice.core.auth.ACCOUNT_REF_FIELD
import splice.core.auth.AccountRef
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.credentials.AccountCredentialIdentitySource
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialEvidence
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialFileEvidenceReader
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialPresence
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
) : RefreshableAuthProvider, AccountCredentialIdentitySource {
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
    private val document = ClaudeFolderDocument(folder, renewal, log)

    override fun observedCredentialKey(): String? = document.read()?.accessToken?.let(document::keyOf)

    /** The account profile verified for this token, so blocks survive token refreshes and daemon restarts. */
    override fun credentialAccount(): String? = observedCredentialKey()?.let { profiles?.read(it) }?.uuid

    override fun credentialIdentity(): CredentialFileIdentity? = credentialEvidence().identity

    override fun credentialEvidence(): CredentialEvidence = if (document.read() != null) {
        CredentialFileEvidenceReader.read(folder.resolve(CREDENTIALS_JSON))
    } else {
        CredentialEvidence(null, CredentialPresence.MISSING)
    }

    override suspend fun answer(): LoginAnswer =
        renewal.answer(document.read()?.let { HeldToken(Credentials.Bearer(it.accessToken), it.expiresAtMs) }, windows)

    /** The folder's access token, rotated first when it is at or inside its proactive window. */
    override suspend fun credentials(): Credentials? = (answer() as? LoginAnswer.Granted)?.credentials

    /** One rotation, coalesced with any already in flight about the same token. */
    override suspend fun refresh(): Credentials? = (exchange() as? RefreshOutcome.Refreshed)?.credentials

    override suspend fun exchange(): RefreshOutcome = renewal.exchange()

    /** What the transport's 401 path calls, with the token the refused request sent. */
    override suspend fun exchange(refused: Credentials?): RefreshOutcome = renewal.exchange(refused)

    override suspend fun describe(): AuthDescription {
        val held = document.read()
        val fields = mutableMapOf("auth_path" to folder.resolve(CREDENTIALS_JSON).toString())
        // A name a person gave beats the email verified from this credential profile.
        val given = ClaudeLoginFactsReader().displayName(folder.resolve(".splice-account.json"))
        given?.let { fields["display_name"] = it }
        held?.let { credential ->
            val key = document.keyOf(credential.accessToken)
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
     *  holds a token other than [prior], the one the caller read (a 401's: the one the refused request sent), a rotation
     *  landed since and is adopted, so two turns that read the same near-expiry token spend its refresh token once. */
    private suspend fun renew(prior: String?): RefreshOutcome {
        val held = document.readHeld().getOrElse { return RefreshOutcome.ReadFailed(it) }
            ?: return RefreshOutcome.NoRefreshToken
        val adopted = prior != null && held.accessToken != prior
        return if (adopted) RefreshOutcome.Refreshed(Credentials.Bearer(held.accessToken)) else rotated(held)
    }

    /** POSTs [held]'s refresh token and keeps what the endpoint granted. */
    private suspend fun rotated(held: ClaudeFolderHeld): RefreshOutcome {
        return when (val attempt = refresh.rotate(held.refreshToken)) {
            is RefreshAttempt.Granted -> {
                val rotated = attempt.tokens
                renewal.persist(document.rewrite(held.document, rotated))
                // Carry the verified account onto the renewed token and reprove in the background.
                document.keyOf(rotated.accessToken)?.let { renewedKey ->
                    document.keyOf(held.accessToken)?.let { profiles?.read(it) }
                        ?.let { profiles?.observed(renewedKey, it) }
                    identities?.reprove(renewedKey, rotated.accessToken)
                }
                RefreshOutcome.Refreshed(Credentials.Bearer(rotated.accessToken))
            }
            is RefreshAttempt.InvalidGrant -> RefreshOutcome.Rejected(INVALID_GRANT_REASON)
            is RefreshAttempt.Denied -> RefreshOutcome.Rejected(attempt.detail)
            is RefreshAttempt.Unavailable -> RefreshOutcome.Unavailable(attempt.detail)
        }
    }
}
