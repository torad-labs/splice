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

import kotlinx.serialization.json.Json
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
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
    /** POST grant_type=refresh_token to auth.kimi.com's token URL; returns rotated tokens or null. */
    private val refreshCall: suspend (refreshToken: String) -> KimiRefreshedTokens?,
) : RefreshableAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val singleFlight = SingleFlight<Credentials?>()

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
        val threshold = maxOf(MIN_PROACTIVE_S, snap.expiresInS / 2)
        if (snap.expiresAtS - nowS >= threshold) return apiKey(snap.access)
        // proactive window: single-flight refresh; on failure serve the current token if still valid.
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
    private suspend fun doRefresh(): RefreshOutcome {
        if (!Files.exists(authPath)) return RefreshOutcome.NoCredentialsFile
        val priorAccess = cache?.snapshot?.access
        return CredentialLock.withLock(authPath) { refreshLocked(priorAccess) }
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

    private suspend fun exchangeRefreshToken(
        refreshToken: String,
        allowRereadRetry: Boolean = true,
    ): RefreshOutcome {
        // Guard the network hop: a thrown refreshCall must degrade to a typed outcome, not blow
        // through SingleFlight uncaught. Rotation is mandatory — a null response means no grant.
        val fresh = runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
            ?: return rejectedOrRetry(refreshToken, allowRereadRetry, "token endpoint returned no rotated tokens")
        writeSecure(authPath, kimiAuthJson(fresh, clock()).toString())
        invalidateCache()
        return RefreshOutcome.Refreshed(apiKey(fresh.accessToken))
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
        return AuthDescription(
            present = present,
            kind = "kimi-oauth",
            fields = buildMap {
                put("auth_path", authPath.toString())
                put("login", "device")
            },
        )
    }

    private companion object {
        const val LOG_TAG = "kimi-auth"
        const val DEFAULT_CACHE_MS = 30_000L

        // proactive-refresh floor: never let a token get within 5 minutes of expiry.
        const val MIN_PROACTIVE_S = 300L
    }
}
