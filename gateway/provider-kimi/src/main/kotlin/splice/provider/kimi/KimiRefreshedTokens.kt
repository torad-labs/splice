// NEW: parsed result of the kimi token-endpoint refresh POST. Split from
// KimiAuthProvider.kt so the refresh ladder is not billed for a field group
// (concentration HIGH, 2026-08-19). Rotation is mandatory — every refresh
// response carries a new refresh_token.
package splice.provider.kimi

import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshOutcome
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.WallClock
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

internal class KimiAuthStore(
    private val authPath: Path,
    private val clock: WallClock,
    private val synthesizeExpiry: SynthesizeExpiry,
    private val log: LogSink,
    private val persist: PersistGranted,
    private val refreshCall: RefreshCall<KimiRefreshedTokens>,
    private val oauth: KimiOAuth = KimiOAuth(),
) {
    internal fun interface SynthesizeExpiry {
        operator fun invoke(mtimeMs: Long): Long
    }

    internal fun interface PersistGranted {
        operator fun invoke(tokens: KimiRefreshedTokens): RefreshOutcome
    }

    @Volatile
    private var cache: Cache? = null

    internal data class Cache(val snapshot: Snapshot, val mtimeMs: Long, val loadedAt: Long)

    internal data class Snapshot(val access: String, val refresh: String?, val expiresAtS: Long, val expiresInS: Long)

    internal fun cachedAccess(): String? = cache?.snapshot?.access

    internal fun clearCache() {
        cache = null
    }

    internal fun readSnapshot(authCacheMs: Long): Snapshot? = Cancellables.runCatchingCancellable {
        if (!Files.exists(authPath)) return@runCatchingCancellable null
        val mtime = Files.getLastModifiedTime(authPath).toMillis()
        val now = clock()
        cache?.let { c ->
            if (c.mtimeMs == mtime && (now - c.loadedAt) < authCacheMs) return@runCatchingCancellable c.snapshot
        }
        oauth.parseSnapshot(authPath, synthesizeExpiry)?.also { cache = Cache(it, mtime, now) }
    }.onFailure {
        log("[kimi-auth] failed to read $authPath: $it — treating as not logged in")
    }.getOrNull()

    internal fun peerRotation(priorAccess: String?, snap: Snapshot?): RefreshOutcome? {
        if (priorAccess == null || snap == null) return null
        if (snap.access == priorAccess) return null
        // Contended-window stat, same as the grok/codex twins: a peer can replace the file between
        // the read that produced [snap] and this one. Unguarded, an IOException escaped
        // refreshLocked() as a crash while every other failure there degrades to an outcome.
        return Cancellables.runCatchingCancellable { Files.getLastModifiedTime(authPath).toMillis() }
            .onFailure { log("[kimi-auth] stat of $authPath failed: $it — skipping peer rotation, refreshing instead") }
            .getOrNull()
            ?.let { mtime ->
                cache = Cache(snap, mtime, clock())
                RefreshOutcome.Refreshed(Credentials.ApiKey(key = snap.access, header = "x-api-key", prefix = ""))
            }
    }

    internal suspend fun refreshLocked(priorAccess: String?): RefreshOutcome {
        val snap = Cancellables.runCatchingCancellable { oauth.parseSnapshot(authPath, synthesizeExpiry) }
            .getOrElse { return RefreshOutcome.ReadFailed(it) }
        peerRotation(priorAccess, snap)?.let { return it }
        val refreshToken = snap?.refresh
        return if (refreshToken == null) RefreshOutcome.NoRefreshToken else exchangeRefreshToken(refreshToken)
    }

    private suspend fun exchangeRefreshToken(
        refreshToken: String,
        allowRereadRetry: Boolean = true,
    ): RefreshOutcome {
        val attempt = Cancellables.runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
        return when (attempt) {
            is RefreshAttempt.Granted -> persist(attempt.tokens)
            is RefreshAttempt.InvalidGrant -> rejectedOrRetry(refreshToken, allowRereadRetry, INVALID_GRANT_REASON)
            is RefreshAttempt.Denied -> rejectedOrRetry(refreshToken, allowRereadRetry, attempt.detail)
        }
    }

    private suspend fun rejectedOrRetry(
        usedRefreshToken: String,
        allowRereadRetry: Boolean,
        reason: String,
    ): RefreshOutcome {
        if (!allowRereadRetry) return RefreshOutcome.Rejected(reason)
        val newToken = Cancellables.runCatchingCancellable { oauth.parseSnapshot(authPath, synthesizeExpiry)?.refresh }
            .getOrElse { return RefreshOutcome.Rejected(reason) }
        return if (newToken != null && newToken != usedRefreshToken) {
            exchangeRefreshToken(newToken, allowRereadRetry = false)
        } else {
            RefreshOutcome.Rejected(reason)
        }
    }

    internal fun describe(mtime: Long?, latch: InvalidGrantLatch): AuthDescription {
        // ast-grep-ignore: kt-no-silent-result-collapse -- introspection display only: a read failure renders as present=false, which is the displayed truth
        val present = Cancellables.runCatchingCancellable {
            Files.exists(authPath) && oauth.parseSnapshot(authPath, synthesizeExpiry) != null
        }.getOrDefault(false)
        return AuthDescription(
            present = present,
            kind = "kimi-oauth",
            fields = buildMap {
                put("auth_path", authPath.toString())
                put("login", "device")
                if (latch.isLatched(mtime)) put("refresh_latched", INVALID_GRANT_REASON)
            },
        )
    }
}
