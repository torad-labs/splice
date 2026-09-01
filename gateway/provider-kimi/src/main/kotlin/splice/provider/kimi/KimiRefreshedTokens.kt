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
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

private const val REFRESH_ERROR_SNIPPET = 160

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
        val mtime = Files.getLastModifiedTime(authPath).toMillis()
        val now = clock()
        cache?.let { c ->
            if (c.mtimeMs == mtime && (now - c.loadedAt) < authCacheMs) return@runCatchingCancellable c.snapshot
        }
        oauth.parseSnapshot(authPath, synthesizeExpiry)?.also { cache = Cache(it, mtime, now) }
    }.getOrElse { failure ->
        // DR-59 (class law): only NoSuch with no NOFOLLOW entry is the quiet not-logged-in null;
        // an untraversable parent or dangling link is a PRESENT credential problem and logs.
        val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
            !Files.exists(authPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (!genuinelyAbsent) {
            log(
                "[kimi-auth] failed to read $authPath: ${SafeFailureText.render(failure)} — " +
                    "no credentials served (NOT a logged-out state)",
            )
        }
        null
    }

    internal fun peerRotation(priorAccess: String?, snap: Snapshot?): RefreshOutcome? {
        if (priorAccess == null || snap == null) return null
        if (snap.access == priorAccess) return null
        // Contended-window stat, same as the grok/codex twins: a peer can replace the file between
        // the read that produced [snap] and this one. Unguarded, an IOException escaped
        // refreshLocked() as a crash while every other failure there degrades to an outcome.
        return Cancellables.runCatchingCancellable { Files.getLastModifiedTime(authPath).toMillis() }
            .onFailure {
                log(
                    "[kimi-auth] stat of $authPath failed: ${SafeFailureText.render(it)} — " +
                        "skipping peer rotation, refreshing instead",
                )
            }
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
        // The confirming reread exists to prove the rejection wasn't a stale-token race. When it
        // FAILS, the rejection is unconfirmed: surface the read failure in the reason (codex twin
        // parity) — the composite string also keeps the invalid_grant latch from arming on it.
        val reread = Cancellables
            .runCatchingCancellable { oauth.parseSnapshot(authPath, synthesizeExpiry)?.refresh }
        val rereadFailure = reread.exceptionOrNull()
        if (rereadFailure != null) {
            val detail = SafeFailureText.render(rereadFailure).take(REFRESH_ERROR_SNIPPET)
            return RefreshOutcome.Rejected("$reason; credential reread failed: $detail")
        }
        val newToken = reread.getOrThrow()
        return if (newToken != null && newToken != usedRefreshToken) {
            exchangeRefreshToken(newToken, allowRereadRetry = false)
        } else {
            RefreshOutcome.Rejected(reason)
        }
    }

    internal fun describe(mtime: Long?, latch: InvalidGrantLatch): AuthDescription {
        val presentOutcome = Cancellables.runCatchingCancellable {
            oauth.parseSnapshot(authPath, synthesizeExpiry) != null
        }
        // ast-grep-ignore: kt-no-silent-result-collapse -- failure consumed below via exceptionOrNull -> read_error
        val present = presentOutcome.getOrDefault(false)
        return AuthDescription(
            present = present,
            kind = "kimi-oauth",
            fields = buildMap {
                put("auth_path", authPath.toString())
                put("login", "device")
                if (latch.isLatched(mtime)) put("refresh_latched", INVALID_GRANT_REASON)
                // DR-59: parseSnapshot already classified — genuine absence returned null quietly,
                // so ANY failure reaching here is indeterminate access, named for the dashboard.
                presentOutcome.exceptionOrNull()?.let { put("read_error", SafeFailureText.render(it)) }
            },
        )
    }
}
