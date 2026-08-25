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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialExpiry
import splice.core.auth.CredentialJson
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.spi.CredentialLock
import splice.spi.SingleFlight
import java.nio.file.Files
import java.nio.file.Path

private const val LOG_TAG = "kimi-auth"
private const val DEFAULT_CACHE_MS = 30_000L

// proactive-refresh floor: never let a token get within 5 minutes of expiry.
private const val MIN_PROACTIVE_S = 300L

// G17 hard floor: below this many seconds of validity the refresh blocks the request.
private const val HARD_FLOOR_S = 60L

public class KimiAuthProvider(
    private val authPath: Path,
    private val authCacheMs: Long = DEFAULT_CACHE_MS,
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
) : RefreshableAuthProvider {

    private val singleFlight = SingleFlight<Credentials?>()
    private val invalidGrantLatch = InvalidGrantLatch()

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
    )

    init {
        // Lifecycle ownership: when prefetchScope ends (Daemon.stop cancels probeScope), cancel the
        // shared refresh so it cannot persist a token after shutdown. Per-request cancellation is
        // unaffected — it only cancels that caller's await, never the SingleFlight scope.
        prefetchScope?.coroutineContext?.get(Job)?.invokeOnCompletion { singleFlight.close() }
    }

    private fun synthesizeExpiry(mtimeMs: Long): Long =
        CredentialExpiry.synthesizedExpiryMs(mtimeMs, clock()) / MS_PER_S

    override suspend fun credentials(): Credentials? {
        val snap = store.readSnapshot(authCacheMs) ?: return null
        val nowS = clock() / MS_PER_S
        val remainingS = snap.expiresAtS - nowS
        if (remainingS >= maxOf(MIN_PROACTIVE_S, snap.expiresInS / 2)) return apiKey(snap.access)
        return proactiveWindowCredentials(snap, nowS, remainingS)
    }

    // G17 two-tier: above the hard floor the request never waits — serve the current token and
    // rotate in the background (SingleFlight dedupes concurrent kicks; measured p90 802ms of
    // request-path stall when this blocked, 2026-07-18). At/below the floor block as before:
    // a nearly-dead token risks a mid-stream 401, which costs more than the wait.
    private suspend fun proactiveWindowCredentials(
        snap: KimiAuthStore.Snapshot,
        nowS: Long,
        remainingS: Long,
    ): Credentials? {
        if (prefetchScope != null && remainingS > HARD_FLOOR_S) {
            prefetchScope.launch { singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) } }
            return apiKey(snap.access)
        }
        val refreshed = singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) }
        return refreshed ?: (if (nowS < snap.expiresAtS) apiKey(snap.access) else null)
    }

    override suspend fun refresh(): Credentials? =
        singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) }

    override fun allowRefreshAfterFailure(status: Int, body: String): Boolean =
        !oauth.isPlanTierRejection(body)

    // Sealed per-mode outcome (discipline L3): a dead refresh token, a transport blip, and a
    // corrupt file are DIFFERENT stories; credentialsOrNull is the single logging flatten.
    // Staged (read → exchange), each stage owning its own failure branches.
    // G1: capture what THIS process last served BEFORE the lock, then re-read authoritatively INSIDE
    // it — so a peer's rotation (landed while we waited on the lock) is seen, not overwritten.
    // G15: gate on a latched confirmed invalid_grant BEFORE any file-content read or network call —
    // a dead token no longer gets re-POSTed every turn. The gate gives way the instant the file's
    // mtime changes (re-login), so a genuinely stale latch never outlives the credentials it named.
    private suspend fun doRefresh(): RefreshOutcome {
        if (!Files.exists(authPath)) return RefreshOutcome.NoCredentialsFile
        val mtime = oauth.kimiAuthMtimeOrNull(authPath, log)
        if (invalidGrantLatch.isLatched(mtime)) return RefreshOutcome.Rejected(INVALID_GRANT_REASON)
        val priorAccess = store.cachedAccess()
        // AUTH-002: wire the daemon log sink so the lock's proceed-unlocked fallback is observable
        // (CredentialLock.withLock's `log` default is a silent no-op) instead of only greppable
        // in a source comment.
        val outcome = CredentialLock.withLock(authPath, log = log) { store.refreshLocked(priorAccess) }
        if (outcome is RefreshOutcome.Rejected && outcome.reason == INVALID_GRANT_REASON) {
            invalidGrantLatch.latch(mtime)
        }
        return outcome
    }

    private fun persistGranted(tokens: KimiRefreshedTokens): RefreshOutcome {
        // The endpoint already rotated the old refresh_token (mandatory rotation) by the time
        // we get here — a throwing write must degrade to a typed PersistFailed, never a raw
        // throw through SingleFlight out of credentials()/refresh(), so the not-yet-expired
        // current token still gets served.
        // SH-10: merge onto the on-disk object — a from-scratch rewrite dropped every
        // field kimi-cli/kimi-code stores beside ours (the 2026-07-18 audit shape grok and
        // codex already fixed). An unreadable file logs and degrades to tokens-only.
        val onDisk = Cancellables.runCatchingCancellable {
            kimiJson.parseToJsonElement(Files.readString(authPath)) as? JsonObject ?: JsonObject(emptyMap())
        }.onFailure {
            log("[kimi-auth] could not re-read $authPath before persist ($it) — writing tokens-only")
        }.getOrNull()
        Cancellables.runCatchingCancellable {
            val merged = CredentialJson.mergedCredentialJson(onDisk, oauth.kimiAuthJson(tokens, clock()))
            writeSecure(authPath, merged.toString())
        }.getOrElse { return RefreshOutcome.PersistFailed("credentials write failed: $it") }
        store.clearCache()
        return RefreshOutcome.Refreshed(apiKey(tokens.accessToken))
    }

    private fun apiKey(token: String): Credentials =
        Credentials.ApiKey(key = token, header = "x-api-key", prefix = "")

    override suspend fun describe(): AuthDescription =
        store.describe(oauth.kimiAuthMtimeOrNull(authPath, log), invalidGrantLatch)

    // Atomic 0600 credential write — routes to the shared primitive, mirroring the private member
    // CodexAuthProvider/GrokAuthProvider already carry.
    private fun writeSecure(path: Path, content: String) {
        SecureFile.writeAtomic0600(path, content)
    }
}
