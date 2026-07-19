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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
import splice.core.auth.credentialsOrNull
import splice.core.util.long
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import splice.spi.CredentialLock
import splice.spi.SingleFlight
import java.nio.file.Files
import java.nio.file.Path

/** Parsed result of the kimi token-endpoint refresh POST; refresh_token rotation is mandatory. */
public data class KimiRefreshedTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val scope: String = "",
    val tokenType: String = "Bearer",
)

public class KimiAuthProvider(
    private val authPath: Path,
    private val authCacheMs: Long = DEFAULT_CACHE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** POST grant_type=refresh_token to auth.kimi.com's token URL; returns the classified attempt. */
    private val refreshCall: suspend (refreshToken: String) -> RefreshAttempt<KimiRefreshedTokens>,
    /** G17: scope for background prefetch in the proactive window; null keeps the blocking path. */
    private val prefetchScope: CoroutineScope? = null,
) : RefreshableAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val singleFlight = SingleFlight<Credentials?>()
    private val invalidGrantLatch = InvalidGrantLatch()

    @Volatile
    private var cache: Cache? = null

    private data class Cache(val snapshot: Snapshot, val mtimeMs: Long, val loadedAt: Long)

    private data class Snapshot(
        val access: String,
        val refresh: String?,
        val expiresAtS: Long,
        val expiresInS: Long,
    )

    override suspend fun credentials(): Credentials? {
        val snap = readSnapshot() ?: return null
        val nowS = clock() / MS_PER_S
        val remainingS = snap.expiresAtS - nowS
        if (remainingS >= maxOf(MIN_PROACTIVE_S, snap.expiresInS / 2)) return apiKey(snap.access)
        return proactiveWindowCredentials(snap, nowS, remainingS)
    }

    // G17 two-tier: above the hard floor the request never waits — serve the current token and
    // rotate in the background (SingleFlight dedupes concurrent kicks; measured p90 802ms of
    // request-path stall when this blocked, 2026-07-18). At/below the floor block as before:
    // a nearly-dead token risks a mid-stream 401, which costs more than the wait.
    private suspend fun proactiveWindowCredentials(snap: Snapshot, nowS: Long, remainingS: Long): Credentials? {
        if (prefetchScope != null && remainingS > HARD_FLOOR_S) {
            prefetchScope.launch { singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG) } }
            return apiKey(snap.access)
        }
        val refreshed = singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG) }
        return refreshed ?: (if (nowS < snap.expiresAtS) apiKey(snap.access) else null)
    }

    override suspend fun refresh(): Credentials? =
        singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG) }

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
        val mtime = kimiAuthMtimeOrNull(authPath)
        if (invalidGrantLatch.isLatched(mtime)) return RefreshOutcome.Rejected(INVALID_GRANT_REASON)
        val priorAccess = cache?.snapshot?.access
        val outcome = CredentialLock.withLock(authPath) { refreshLocked(priorAccess) }
        if (outcome is RefreshOutcome.Rejected && outcome.reason == INVALID_GRANT_REASON) {
            invalidGrantLatch.latch(mtime)
        }
        return outcome
    }

    // Runs holding the cross-process lock: re-read fresh, short-circuit if a peer already rotated,
    // else exchange. Split out of doRefresh so withLock's lambda stays a single call.
    private suspend fun refreshLocked(priorAccess: String?): RefreshOutcome {
        val snap = runCatchingCancellable { parseSnapshot() }
            .getOrElse { return RefreshOutcome.ReadFailed(it) }
        peerRotation(priorAccess, snap)?.let { return it }
        val refreshToken = snap?.refresh
        return if (refreshToken == null) RefreshOutcome.NoRefreshToken else exchangeRefreshToken(refreshToken)
    }

    // A peer process may have rotated the token while we waited on the lock: if the freshly-read
    // access token differs from what THIS process last served, adopt it and skip the POST. Token
    // identity — not the expiry-window heuristic — is the unambiguous signal.
    private fun peerRotation(priorAccess: String?, snap: Snapshot?): RefreshOutcome? {
        if (priorAccess == null || snap == null) return null
        if (snap.access == priorAccess) return null
        cache = Cache(snap, Files.getLastModifiedTime(authPath).toMillis(), clock())
        return RefreshOutcome.Refreshed(apiKey(snap.access))
    }

    // G15: InvalidGrant flows through the SAME rejectedOrRetry() G1 reread-once dance as any other
    // rejection — a "confirmed" invalid_grant (the one doRefresh() latches on) is one that survived
    // that race check, exactly matching the gap's "post-G1 re-read" requirement.
    private suspend fun exchangeRefreshToken(
        refreshToken: String,
        allowRereadRetry: Boolean = true,
    ): RefreshOutcome {
        // Guard the network hop: a thrown refreshCall must degrade to a typed outcome, not blow
        // through SingleFlight uncaught. Rotation is mandatory — a Denied response means no grant.
        val attempt = runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
        return when (attempt) {
            is RefreshAttempt.Granted -> {
                writeSecure(authPath, kimiAuthJson(attempt.tokens, clock()).toString())
                invalidateCache()
                RefreshOutcome.Refreshed(apiKey(attempt.tokens.accessToken))
            }
            is RefreshAttempt.InvalidGrant -> rejectedOrRetry(refreshToken, allowRereadRetry, INVALID_GRANT_REASON)
            is RefreshAttempt.Denied -> rejectedOrRetry(refreshToken, allowRereadRetry, attempt.detail)
        }
    }

    // Bounded one-shot retry: an endpoint rejection MIGHT be a stale-token race — if disk now shows a
    // DIFFERENT refresh token (a peer rotated between our read and the POST landing), retry once
    // against it. Capped at exactly one extra POST (the retry passes allowRereadRetry=false); never
    // loops, and never re-POSTs the identical dead token (the disk-differs gate).
    private suspend fun rejectedOrRetry(
        usedRefreshToken: String,
        allowRereadRetry: Boolean,
        reason: String,
    ): RefreshOutcome {
        if (!allowRereadRetry) return RefreshOutcome.Rejected(reason)
        val newToken = runCatchingCancellable { parseSnapshot()?.refresh }
            .getOrElse { return RefreshOutcome.Rejected(reason) }
        return if (newToken != null && newToken != usedRefreshToken) {
            exchangeRefreshToken(newToken, allowRereadRetry = false)
        } else {
            RefreshOutcome.Rejected(reason)
        }
    }

    private fun apiKey(token: String): Credentials =
        Credentials.ApiKey(key = token, header = "x-api-key", prefix = "")

    private fun readSnapshot(): Snapshot? = runCatchingCancellable {
        if (!Files.exists(authPath)) return@runCatchingCancellable null
        val mtime = Files.getLastModifiedTime(authPath).toMillis()
        val now = clock()
        cache?.let { c ->
            if (c.mtimeMs == mtime && (now - c.loadedAt) < authCacheMs) {
                return@runCatchingCancellable c.snapshot
            }
        }
        parseSnapshot()?.also { cache = Cache(it, mtime, now) }
    }.onFailure {
        System.err.println("[kimi-auth] failed to read $authPath: $it — treating as not logged in")
    }.getOrNull()

    private fun parseSnapshot(): Snapshot? {
        if (!Files.exists(authPath)) return null
        val obj = json.parseToJsonElement(Files.readString(authPath)).jsonObjectOrEmpty()
        val access = obj.str("access_token") ?: return null
        return Snapshot(
            access = access,
            refresh = obj.str("refresh_token"),
            expiresAtS = obj.long("expires_at") ?: 0L,
            expiresInS = obj.long("expires_in") ?: 0L,
        )
    }

    public fun invalidateCache() {
        cache = null
    }

    override suspend fun describe(): AuthDescription {
        // ast-grep-ignore: kt-no-silent-result-collapse -- introspection display only: a read failure renders as present=false, which is the displayed truth
        val present = runCatchingCancellable {
            Files.exists(authPath) && parseSnapshot() != null
        }.getOrDefault(false)
        val mtime = kimiAuthMtimeOrNull(authPath)
        return AuthDescription(
            present = present,
            kind = "kimi-oauth",
            fields = buildMap {
                put("auth_path", authPath.toString())
                put("login", "device")
                if (invalidGrantLatch.isLatched(mtime)) put("refresh_latched", INVALID_GRANT_REASON)
            },
        )
    }

    private companion object {
        const val LOG_TAG = "kimi-auth"
        const val DEFAULT_CACHE_MS = 30_000L

        // proactive-refresh floor: never let a token get within 5 minutes of expiry.
        const val MIN_PROACTIVE_S = 300L

        // G17 hard floor: below this many seconds of validity the refresh blocks the request.
        const val HARD_FLOOR_S = 60L
    }
}

// G15: best-effort mtime probe for the invalid_grant latch gate. Top-level (not a class member) so
// KimiAuthProvider stays under the TooManyFunctions ceiling; shared by doRefresh() and describe().
// The failure is logged, not swallowed, before collapsing to null — a stat failure is "unknown",
// which InvalidGrantLatch treats as fail-open (never suppresses), NOT "file unchanged".
private fun kimiAuthMtimeOrNull(authPath: Path): Long? = runCatchingCancellable {
    Files.getLastModifiedTime(authPath).toMillis()
}.onFailure {
    System.err.println("[kimi-auth] failed to stat $authPath mtime: $it — invalid_grant latch check skipped")
}.getOrNull()
