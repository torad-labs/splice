// NEW: Kimi (Moonshot) runtime auth — reads the FLAT kimi-cli-compatible auth file
// (~/.kimi/credentials/kimi-code.json) and yields Credentials.ApiKey with header `x-api-key`,
// empty prefix. The Anthropic surface of api.kimi.com/coding authenticates via x-api-key, NOT
// Authorization/Bearer (verified in kimi-code source + e2e). Mirrors GrokAuthProvider's structure
// (mtime+TTL cache 30s, SingleFlight refresh, 0600 write, masked describe) but adds PROACTIVE
// refresh: when the token is within max(300, expires_in/2) seconds of expiry, refresh before
// serving. Rotation is MANDATORY — every refresh response carries a new refresh_token, always
// persisted. A refresh failure on a not-yet-expired token still serves the current token.
// Failure visibility (discipline L1): every auth-critical Result collapse consumes the failure
// with a stderr line first — a corrupt auth file must never masquerade as "not logged in".
package splice.provider.kimi

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialExpiry
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.CredentialJson
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
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

private const val LOG_TAG = "kimi-auth"

// proactive-refresh floor: never let a token get within 5 minutes of expiry.
private const val MIN_PROACTIVE_S = 300L

// G17 hard floor: below this many seconds of validity the refresh blocks the request.
private const val HARD_FLOOR_S = 60L

// The largest count of seconds that still fits in milliseconds.
private const val MAX_S = Long.MAX_VALUE / MS_PER_S

public class KimiAuthProvider(
    private val authPath: Path,
    private val authCacheMs: Long,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    /** POST grant_type=refresh_token to auth.kimi.com's token URL; returns the classified attempt. */
    private val refreshCall: RefreshCall<KimiRefreshedTokens>,
    /** G17: scope for background prefetch in the proactive window; null keeps the blocking path. */
    private val prefetchScope: CoroutineScope? = null,
    /** Daemon log sink (Main.persistentLogger): writes BOTH stderr and daemon.log, which is what
     *  /mgmt/logs tails. A bare System.err.println reaches stderr ONLY, so its line never appears in
     *  the log endpoint — the failure you most want to read is the one you cannot (wall
     *  kt-no-println, 2026-07-27). Defaults to a no-op so tests need not thread it; the daemon
     *  always injects the real sink. */
    private val log: LogSink = LogSink(DaemonLog::write),
) : RefreshableAuthProvider, AccountCredentialIdentitySource {

    // Audit, Nov 9, 2026: the ladder (serve, prefetch, wait near expiry, stat, latch, lock, persist) is the shared
    // TokenRenewal; what stays here is kimi's own: the flat file's shape, its windows and the merge.
    private val renewal: TokenRenewal = TokenRenewal(
        authPath,
        LOG_TAG,
        clock,
        log,
        prefetchScope,
        RenewalStage { prior -> store.refreshLocked(prior) },
    )

    // The plan-tier probe and the auth-file shaper moved to KimiOAuth with the rest of the kimi
    // OAuth wire helpers (HD-M5); this class reaches them through one collaborator.
    private val oauth = KimiOAuth()
    private val store = KimiAuthStore(
        authPath,
        clock,
        KimiAuthStore.SynthesizeExpiry { synthesizeExpiry(it) },
        log,
        KimiAuthStore.PersistGranted { persistGranted(it) },
        refreshCall,
        renewal.text,
    )

    private fun synthesizeExpiry(mtimeMs: Long): Long =
        CredentialExpiry.synthesizedExpiryMs(mtimeMs, clock()) / MS_PER_S

    // G17 two-tier, run by the shared renewal: the proactive window is max(300 s, expires_in / 2) before expiry; above
    // the hard floor the request never waits (serve the current token and rotate in the background; measured p90
    // 802 ms of request-path stall when this blocked, 2026-07-18); at or below it, or with no prefetch scope, it waits,
    // still serving a token that has not expired.
    override suspend fun answer(): LoginAnswer {
        val snap = store.readSnapshot(authCacheMs)
        // The file's unit is unix SECONDS; one too large for milliseconds saturates (DR-177), never wraps.
        val held = snap?.let { HeldToken(apiKey(it.access), it.expiresAtS.coerceIn(-MAX_S, MAX_S) * MS_PER_S) }
        val proactiveS = maxOf(MIN_PROACTIVE_S, (snap?.expiresInS ?: 0L) / 2).coerceAtMost(MAX_S)
        return renewal.answer(held, RenewalWindows(proactiveS * MS_PER_S, HARD_FLOOR_S * MS_PER_S))
    }

    override suspend fun credentials(): Credentials? = (answer() as? LoginAnswer.Granted)?.credentials

    override suspend fun refresh(): Credentials? = (exchange() as? RefreshOutcome.Refreshed)?.credentials

    override suspend fun exchange(): RefreshOutcome = renewal.exchange()

    // Failure-handling slice 3: Kimi's plan-tier 401 is read as the plan limit it is (KimiReplies), never as a
    // refused login, so no refresh is planned for it and this provider carries no rule about a body.

    private fun persistGranted(tokens: KimiRefreshedTokens): RefreshOutcome {
        // The endpoint already rotated the old refresh_token (mandatory rotation) by the time we get here: a write
        // that fails is kept by the renewal and served from memory until it lands (audit, Nov 9, 2026).
        // SH-10: merge onto the on-disk object — a from-scratch rewrite dropped every
        // field kimi-cli/kimi-code stores beside ours (the 2026-07-18 audit shape grok and
        // codex already fixed). An unreadable file logs and degrades to tokens-only.
        val onDisk = Cancellables.runCatchingCancellable {
            kimiJson.parseToJsonElement(renewal.text.read()) as? JsonObject ?: JsonObject(emptyMap())
        }.onFailure {
            log(
                "[kimi-auth] could not re-read $authPath before persist " +
                    "(${SafeFailureText.render(it)}); writing tokens-only",
            )
        }.getOrNull()
        renewal.persist(CredentialJson.mergedCredentialJson(onDisk, oauth.kimiAuthJson(tokens, clock())).toString())
        store.clearCache()
        return RefreshOutcome.Refreshed(apiKey(tokens.accessToken))
    }

    private fun apiKey(token: String): Credentials =
        Credentials.ApiKey(key = token, header = "x-api-key", prefix = "")

    override suspend fun describe(): AuthDescription = store.describe(renewal.identity(), renewal.latch)

    override fun observedCredentialKey(): String? = store.readSnapshot(0L)?.access?.let {
        CredentialKey.fromCredentials(apiKey(it))
    }

    override fun credentialIdentity(): CredentialFileIdentity? = credentialEvidence().identity

    override fun credentialPresence(): CredentialPresence = credentialEvidence().presence

    override fun credentialEvidence(): CredentialEvidence =
        CredentialFileEvidenceReader.read(authPath)
}
