// NEW: merge freshly refreshed tokens onto the existing auth.json. Split from
// CodexAuthProvider.kt so the refresh ladder is not billed for a JSON fold
// (concentration HIGH, 2026-08-19). persistRotation and writeSecure stay put.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.auth.Credentials
import splice.core.auth.RefreshOutcome
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.core.util.WallClockIso
import java.nio.file.Files
import java.nio.file.Path

private const val FIELD_TOKENS = "tokens"
private const val FIELD_ACCESS_TOKEN = "access_token"
private const val FIELD_REFRESH_TOKEN = "refresh_token"
private const val FIELD_ID_TOKEN = "id_token"
private const val FIELD_LAST_REFRESH = "last_refresh"
private const val FIELD_ACCOUNT_ID = "account_id"
private const val FIELD_EXP = "exp"
private const val MS_PER_S = 1000L

internal fun interface SynthesizeExpiry {
    operator fun invoke(mtimeMs: Long): Long
}

internal class CodexAuthJson(
    private val nowIso: WallClockIso,
    private val authPath: Path,
    private val clock: WallClock,
    private val synthesizeExpiry: SynthesizeExpiry,
    private val log: LogSink,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val oauth: CodexOAuth = CodexOAuth(),
) {
    @Volatile
    private var cache: Cache? = null

    internal data class Cache(val snapshot: Snapshot, val mtimeMs: Long, val loadedAt: Long, val sizeBytes: Long)

    internal data class Snapshot(val access: String, val accountId: String?, val expiresAtMs: Long?)

    /** Merge the freshly refreshed tokens onto the existing auth.json, preserving every field the
     *  refresh response didn't replace (id_token/refresh_token only overwritten when present). */
    fun mergedAuthJson(
        raw: JsonObject,
        tokens: JsonObject,
        fresh: RefreshedTokens,
        access: String,
    ): JsonObject {
        val nextTokens = buildJsonObject {
            tokens.forEach { (k, v) -> put(k, v) }
            put(FIELD_ACCESS_TOKEN, JsonPrimitive(access))
            fresh.refreshToken?.let { put(FIELD_REFRESH_TOKEN, JsonPrimitive(it)) }
            fresh.idToken?.let { put(FIELD_ID_TOKEN, JsonPrimitive(it)) }
        }
        return buildJsonObject {
            raw.forEach { (k, v) -> if (k != FIELD_TOKENS && k != FIELD_LAST_REFRESH) put(k, v) }
            put(FIELD_TOKENS, nextTokens)
            put(FIELD_LAST_REFRESH, JsonPrimitive(nowIso()))
        }
    }

    internal fun readSnapshot(authCacheMs: Long): Snapshot? = Cancellables.runCatchingCancellable {
        val mtime = Files.getLastModifiedTime(authPath).toMillis()
        val size = Files.size(authPath)
        val now = clock()
        cache?.let { c ->
            val sameFile = c.mtimeMs == mtime && c.sizeBytes == size
            if (sameFile && (now - c.loadedAt) < authCacheMs) {
                return@runCatchingCancellable c.snapshot
            }
        }
        val tokens = json.parseToJsonElement(Files.readString(authPath)).jsonObject[FIELD_TOKENS] as? JsonObject
        JsonScalars.str(tokens, FIELD_ACCESS_TOKEN)?.let { access ->
            val accountId = JsonScalars.str(tokens, FIELD_ACCOUNT_ID)
            // SH-01 (G18's codex twin): a token with no decodable exp was cached FOREVER — no
            // proactive refresh, first signal a mid-turn 401. Shared policy: synthesize mtime+TTL.
            val expiresAtMs = JsonScalars.long(oauth.decodeJwtClaims(access), FIELD_EXP)?.let { it * MS_PER_S }
                ?: synthesizeExpiry(mtime)
            val snapshot = Snapshot(access, accountId, expiresAtMs)
            cache = Cache(snapshot, mtime, now, size)
            snapshot
        }
    }.getOrElse { failure ->
        logUnlessGenuinelyAbsent(failure)
        null
    }

    /** DR-59 (class law): only NoSuch with no NOFOLLOW entry is the quiet not-logged-in null;
     *  an untraversable parent or dangling link is a PRESENT credential problem and logs. */
    private fun logUnlessGenuinelyAbsent(failure: Throwable) {
        val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
            !Files.exists(authPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (!genuinelyAbsent) {
            log(
                "[codex-auth] failed to read $authPath: ${SafeFailureText.render(failure)} — " +
                    "no credentials served (NOT a logged-out state)",
            )
        }
    }

    internal fun cachedAccess(): String? = cache?.snapshot?.access

    internal fun clearCache() {
        cache = null
    }

    internal fun parseObject(): JsonObject = json.parseToJsonElement(Files.readString(authPath)).jsonObject

    // A peer (another process, or the official codex CLI) may have rotated the token while we waited
    // on the lock: if the freshly-read access token differs from what THIS process last served, adopt
    // it and skip the POST. Token identity — not the `exp` claim — is the unambiguous signal.
    internal fun peerRotation(priorAccess: String?, tokens: JsonObject?): RefreshOutcome? {
        val freshAccess = JsonScalars.str(tokens, FIELD_ACCESS_TOKEN)
        if (priorAccess == null || freshAccess == null) return null
        if (freshAccess == priorAccess) return null
        val accountId = JsonScalars.str(tokens, FIELD_ACCOUNT_ID)
        val expiresAtMs = JsonScalars.long(oauth.decodeJwtClaims(freshAccess), FIELD_EXP)?.let { it * MS_PER_S }
        val snapshot = Snapshot(freshAccess, accountId, expiresAtMs)
        // Two unguarded stats in the contended window: a peer (another splice, the official codex
        // CLI) can replace the file between the read that produced [tokens] and these calls. Both
        // now degrade to "no peer rotation, do the real refresh" rather than escaping as a crash.
        return Cancellables.runCatchingCancellable {
            Cache(snapshot, Files.getLastModifiedTime(authPath).toMillis(), clock(), Files.size(authPath))
        }
            .onFailure {
                log("[codex-auth] stat of $authPath failed: $it — skipping peer rotation, refreshing instead")
            }
            .getOrNull()
            ?.let { fresh ->
                cache = fresh
                RefreshOutcome.Refreshed(Credentials.Bearer(freshAccess, accountId))
            }
    }
}
