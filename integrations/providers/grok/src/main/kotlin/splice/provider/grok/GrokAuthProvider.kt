// NEW: xAI Grok OAuth runtime auth — the SuperGrok/X-Premium+ browser login (GrokOAuth), NOT an
// api key. Mirrors CodexAuthProvider: cached ~/.grok/auth.json read (path+mtime+TTL), single-flight
// refresh (grant_type=refresh_token, 0600 write, cache invalidation), masked introspection.
// Grok tokens carry no account id (no ChatGPT-Account-ID header), so Bearer.accountId is null.
// PROACTIVE refresh (grok-dead-head incident, 2026-07-18): xAI reports an expired token as 403
// (not 401), so the reactive 401-refresh path never fired and the head served a dead token
// until manual re-login. Like KimiAuthProvider: when the file's `expires` (ms epoch, written by
// the official grok CLI and by us) is within the proactive window, refresh BEFORE serving; a
// failed refresh on a not-yet-expired token still serves the current one.
// TWO-TIER proactive refresh (G17, 2026-07-19): a blocking refresh inside the whole 5-minute
// window stalls every request that lands in it (UpstreamClient.post() calls credentials()
// synchronously per attempt). Above STALE_FLOOR_MS, kick a single-flight refresh on an owned
// background scope and serve the current token immediately; only below the floor — close enough
// to hard expiry that risking a stale token is worse than the wait — do we still block, exactly
// as before.
// Failure visibility (discipline L1): every auth-critical Result collapse consumes the failure
// with a stderr line first — a corrupt auth file must never masquerade as "not logged in".
// Synthesized expiry for missing `expires` (G18, 2026-07-19): a file without a top-level `expires`
// (legacy shape, or a foreign CLI write that stripped it) was treated as never-expiring — no
// proactive refresh, no eventual expiry. readSnapshot() now synthesizes expiresAtMs = mtime + 4h
// so those files still age out and re-refresh through the same tiers above.
package splice.provider.grok

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialExpiry
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
import splice.core.auth.SYNTHETIC_EXPIRY_TTL_MS
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.core.util.WallClockIso
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.credentials.AccountCredentialIdentitySource
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialEvidence
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialFileEvidenceReader
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialPresence
import splice.upstream.credentials.HeldToken
import splice.upstream.credentials.RenewalStage
import splice.upstream.credentials.RenewalWindows
import splice.upstream.credentials.TokenRenewal
import java.nio.file.Path
import java.time.Instant

internal const val LOG_TAG = "grok-auth"

// SH-02(b): CLIProxyAPI's refreshIneffectiveBackoff value — long enough to stop a tight
// success/re-check loop, short enough that a genuinely recovering endpoint retries soon.
private const val REFRESH_INEFFECTIVE_BACKOFF_MS = 30_000L
private const val MS_PER_S = 1000L

// Why a dead token inside the SH-02(b) backoff is not renewed: the renewal before it granted one that expires inside
// the stale floor, and another would too.
private const val INEFFECTIVE_RENEWAL =
    "the last renewal granted a token that expires inside the stale floor; backing off"

/** Refresh this long before `expires` — well inside a 6h grok token, generous vs clock skew. */
internal const val PROACTIVE_WINDOW_MS = 300_000L

/** Below this, block instead of prefetching: comfortably above the refreshCall's measured
 *  RTT (sub-second) and well below the 300s window, so most of the window stays non-blocking. */
private const val STALE_FLOOR_MS = 30_000L

public class GrokAuthProvider(
    private val authPath: Path,
    private val authCacheMs: Long,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val nowIso: WallClockIso = WallClockIso { Instant.ofEpochMilli(System.currentTimeMillis()).toString() },
    /** POST grant_type=refresh_token to auth.x.ai's token URL; returns the classified attempt. */
    private val refreshCall: RefreshCall<GrokRefreshedTokens>,
    // Injectable so the daemon runs the prefetch LAUNCH on probeScope and tests stay self-contained.
    // SingleFlight isolates the shared refresh from any single request's await cancellation (a peer
    // awaiting it must not be killed); the init block below ties that shared refresh to THIS scope's
    // lifetime, so Daemon.stop (probeScope.cancel) cancels an in-flight refresh instead of letting
    // it write the token file post-shutdown (review 2026-07-23).
    // HD-19: LifecycleScope is the NAMED owner the bare CoroutineScope(...) factory lacked, and it
    // applies the same SupervisorJob on the same background dispatcher — identical context, and the
    // daemon still overrides this default with its own probeScope in production.
    private val prefetchScope: CoroutineScope = LifecycleScope(ProcessDispatchers().background()),
    /** Daemon log sink (Main.persistentLogger): writes BOTH stderr and daemon.log, which is what
     *  /mgmt/logs tails. A bare System.err.println reaches stderr ONLY, so its line never appears in
     *  the log endpoint — the failure you most want to read is the one you cannot (wall
     *  kt-no-println, 2026-07-27). Defaults to a no-op so tests need not thread it; the daemon
     *  always injects the real sink. */
    private val log: LogSink = LogSink(DaemonLog::write),
) : RefreshableAuthProvider, AccountCredentialIdentitySource {

    private val json = Json { ignoreUnknownKeys = true }

    // Audit, Nov 9, 2026: the ladder (serve, prefetch, wait near expiry, stat, latch, lock, persist) is the shared
    // TokenRenewal; what stays here is grok's own: auth.json's shape, its `expires`, the merge and the SH-02 backoff.
    private val windows = RenewalWindows(PROACTIVE_WINDOW_MS, STALE_FLOOR_MS)
    private val renewal: TokenRenewal = TokenRenewal(
        authPath,
        LOG_TAG,
        clock,
        log,
        prefetchScope,
        RenewalStage { prior ->
            authFile.refreshLocked(prior, PersistRotation { rt, tokens, access -> persistRotation(rt, tokens, access) })
        },
    )

    private val authJson = GrokAuthJson(
        authPath,
        json,
        log,
        nowIso,
        clock,
        GrokAuthJson.SynthesizeExpiry { mtimeMs, nowMs -> synthesizeExpiry(mtimeMs, nowMs) },
        renewal.text,
    )

    private fun synthesizeExpiry(mtimeMs: Long, nowMs: Long): Long =
        CredentialExpiry.synthesizedExpiryMs(mtimeMs, nowMs)
    private val authFile = GrokAuthDescribe(authPath, authJson, renewal, refreshCall)

    private val forbidden = GrokForbidden(authJson, clock, log)

    // SH-02(b): set when a refresh persisted an expiry still inside the stale floor; the blocking
    // tier serves the current token until the backoff lapses.
    @Volatile
    // MIN_VALUE/2, not MIN_VALUE: `clock() - MIN_VALUE` overflows negative and would read as
    // "backoff active" from boot with small/injected clocks.
    private var lastIneffectiveRefreshAtMs: Long = Long.MIN_VALUE / 2

    // Three tiers by remaining time-to-expiry (G17), run by the shared renewal: outside the proactive window serve
    // as-is; above the stale floor prefetch in the background and serve the current token; below the floor wait for
    // the renewal, still serving a token that has not expired. expiresAt is always populated (real, or synthesized off
    // mtime by readSnapshot, G18).
    //
    // SH-02(b), grok's own rule: when the last renewal succeeded without advancing past the stale floor, another one
    // would too, so the floor serves the current token through the backoff instead of burning a rotating refresh token
    // per request. DR-146: only while it is actually alive; a dead one is the renewal's refusal, never a token sent to
    // collect a 403.
    override suspend fun answer(): LoginAnswer {
        val snap = authJson.readSnapshot(authCacheMs)
        val held = snap?.let { HeldToken(Credentials.Bearer(it.access, null), it.expiresAtMs) }
        val expiresAt = held?.expiresAtMs
        val backingOff = expiresAt != null && expiresAt - clock() < STALE_FLOOR_MS &&
            clock() - lastIneffectiveRefreshAtMs < REFRESH_INEFFECTIVE_BACKOFF_MS
        return when {
            !backingOff -> renewal.answer(held, windows)
            clock() < expiresAt -> renewal.serve(held.credentials)
            else -> LoginAnswer.Unrenewed(RefreshOutcome.Rejected(INEFFECTIVE_RENEWAL))
        }
    }

    override suspend fun credentials(): Credentials? = (answer() as? LoginAnswer.Granted)?.credentials

    override suspend fun refresh(): Credentials? = (exchange() as? RefreshOutcome.Refreshed)?.credentials

    override suspend fun exchange(): RefreshOutcome = renewal.exchange()

    override fun observedCredentialKey(): String? = authJson.readSnapshot(0L)?.access?.let {
        CredentialKey.fromCredentials(Credentials.Bearer(it))
    }

    override fun allowRefreshAfterFailure(deadCredential: Boolean): Boolean =
        forbidden.allowRefreshAfterFailure(deadCredential)

    private fun persistRotation(refreshToken: String, fresh: GrokRefreshedTokens, access: String): RefreshOutcome {
        // SH-02(a): an endpoint response with no expires_in used to persist a NULL expiry, and the
        // merge then carried the stale on-disk value — the very next credentials() re-entered the
        // blocking tier and refreshed AGAIN, per request, burning rotating refresh tokens (the
        // 2026-07-18 credential-death shape). A just-minted token is not older than the one it
        // replaced: synthesize now+TTL (the SH-01 shared policy) so the expiry always advances.
        // Review finding: synthesizedExpiryMs(clock(), clock()) read the clock twice and clamped
        // the second read against the first, making the clamp a guaranteed no-op — there is no
        // real credential-file mtime here to clamp, only "now" itself, so write that directly.
        // DR-177: the same conversion the persist path uses, and for the same reason — a wrapped
        // expiry here reads as expired forever, which is a refresh per turn rather than one.
        val expiresAtMs = fresh.expiresIn?.let { CredentialExpiry.expiryFromNowMs(clock(), it) }
            ?: (clock() + SYNTHETIC_EXPIRY_TTL_MS)
        // SH-02(b): even with (a), a sub-floor grant (expires_in shorter than the stale floor)
        // leaves the next read inside the blocking tier — a SUCCESSFUL refresh that cannot satisfy
        // the tier logic. Back off instead of looping (CLIProxyAPI refreshIneffectiveBackoff),
        // and log once per trip.
        if (expiresAtMs - clock() < STALE_FLOOR_MS) {
            lastIneffectiveRefreshAtMs = clock()
            log(
                "[$LOG_TAG] refresh succeeded but expiry did not advance past the stale floor; " +
                    "backing off ${REFRESH_INEFFECTIVE_BACKOFF_MS / MS_PER_S}s",
            )
        }
        // The endpoint already consumed the old refresh_token (Granted) by the time we get here: a write that fails is
        // kept by the renewal and served from memory until it lands (audit, Nov 9, 2026).
        renewal.persist(authJson.mergedAuthJson(access, fresh.refreshToken ?: refreshToken, expiresAtMs).toString())
        authJson.clearCache()
        return RefreshOutcome.Refreshed(Credentials.Bearer(access, null))
    }

    override suspend fun describe(): AuthDescription = authFile.describe()

    override fun credentialIdentity(): CredentialFileIdentity? = credentialEvidence().identity

    override fun credentialPresence(): CredentialPresence = credentialEvidence().presence

    override fun credentialEvidence(): CredentialEvidence =
        CredentialFileEvidenceReader.read(authPath)
}
