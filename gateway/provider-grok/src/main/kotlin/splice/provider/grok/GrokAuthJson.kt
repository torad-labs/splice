// NEW: merge freshly refreshed tokens onto the existing grok auth.json.
// Split from GrokAuthProvider.kt so the refresh ladder is not billed for a
// JSON fold (concentration HIGH, 2026-08-19). persistRotation, the SH-02
// strings, and writeSecure stay on the provider.
package splice.provider.grok

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
private const val FIELD_LAST_REFRESH = "last_refresh"
private const val FIELD_EXPIRES = "expires"

internal class GrokAuthJson(
    private val authPath: Path,
    private val json: Json,
    private val log: LogSink,
    private val nowIso: WallClockIso,
    private val clock: WallClock,
    private val synthesizeExpiry: SynthesizeExpiry,
) {
    internal fun interface SynthesizeExpiry {
        operator fun invoke(mtimeMs: Long, nowMs: Long): Long
    }

    @Volatile
    private var cache: Cache? = null

    internal data class Cache(val snapshot: Snapshot, val mtimeMs: Long, val loadedAt: Long)

    internal data class Snapshot(val access: String, val expiresAtMs: Long?)

    internal fun cachedAccess(): String? = cache?.snapshot?.access

    internal fun clearCache() {
        cache = null
    }

    /** MERGE into the on-disk object — a from-scratch rewrite dropped `expires` and every field the
     *  official grok CLI stores beside ours, corrupting the shared file for it (audit 2026-07-18;
     *  the codex twin already merged correctly). `expires` is OVERWRITTEN when the refresh response
     *  carried expires_in — keeping the old value would leave a stale past expiry that re-triggers
     *  the proactive refresh on every turn. */
    fun mergedAuthJson(access: String, refresh: String, expiresAtMs: Long?): JsonObject {
        val onDisk = Cancellables.runCatchingCancellable {
            json.parseToJsonElement(Files.readString(authPath)).jsonObject
        }.onFailure {
            log(
                "[grok-auth] re-read of $authPath for merge failed: ${SafeFailureText.render(it)} — " +
                    "writing tokens-only file",
            )
        }.getOrNull() ?: JsonObject(emptyMap())
        val oldTokens = onDisk[FIELD_TOKENS] as? JsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            onDisk.forEach { (k, v) -> if (!replacedTopLevel(k, expiresAtMs)) put(k, v) }
            put(FIELD_TOKENS, mergedTokens(oldTokens, access, refresh))
            expiresAtMs?.let { put(FIELD_EXPIRES, JsonPrimitive(it)) }
            put(FIELD_LAST_REFRESH, JsonPrimitive(nowIso()))
        }
    }

    // Top-level keys overwritten rather than carried over: the tokens object, last_refresh, and
    // expires (only when the refresh response carried a new expiry).
    private fun replacedTopLevel(key: String, expiresAtMs: Long?): Boolean =
        key == FIELD_TOKENS || key == FIELD_LAST_REFRESH || (key == FIELD_EXPIRES && expiresAtMs != null)

    private fun mergedTokens(oldTokens: JsonObject, access: String, refresh: String): JsonObject =
        buildJsonObject {
            oldTokens.forEach { (k, v) -> if (k != FIELD_ACCESS_TOKEN && k != FIELD_REFRESH_TOKEN) put(k, v) }
            put(FIELD_ACCESS_TOKEN, JsonPrimitive(access))
            put(FIELD_REFRESH_TOKEN, JsonPrimitive(refresh))
        }

    internal fun readSnapshot(authCacheMs: Long): Snapshot? = Cancellables.runCatchingCancellable {
        val mtime = Files.getLastModifiedTime(authPath).toMillis()
        val now = clock()
        cache?.let { c ->
            if (c.mtimeMs == mtime && (now - c.loadedAt) < authCacheMs) {
                return@runCatchingCancellable c.snapshot
            }
        }
        // G18: a file with no top-level `expires` (legacy shape, or a foreign CLI write that
        // stripped it) is otherwise never-expiring — synthesize a ceiling off the mtime already
        // read above, no new I/O.
        parseSnapshot()
            // SH-01: shared policy
            ?.let { it.copy(expiresAtMs = it.expiresAtMs ?: synthesizeExpiry(mtime, now)) }
            ?.also { cache = Cache(it, mtime, now) }
    }.getOrElse { failure ->
        // DR-59 (class law): only NoSuch with no NOFOLLOW entry is the quiet not-logged-in null;
        // an untraversable parent or dangling link is a PRESENT credential problem and logs.
        val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
            !Files.exists(authPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (!genuinelyAbsent) {
            log(
                "[grok-auth] failed to read $authPath: ${SafeFailureText.render(failure)} — " +
                    "no credentials served (NOT a logged-out state)",
            )
        }
        null
    }

    internal fun parseSnapshot(): Snapshot? {
        // DR-59: the read IS the absence probe (the old exists() pre-gate read an inaccessible
        // file as logged-out). Genuine absence returns null; anything else throws into the
        // caller's wrapper (readSnapshot's classified null, refreshLocked's ReadFailed).
        val raw = Cancellables.runCatchingCancellable { Files.readString(authPath) }
            .getOrElse { failure ->
                val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                    !Files.exists(authPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if (genuinelyAbsent) return null
                throw failure
            }
        val onDisk = json.parseToJsonElement(raw).jsonObject
        val access = JsonScalars.str((onDisk[FIELD_TOKENS] as? JsonObject)?.get(FIELD_ACCESS_TOKEN)) ?: return null
        return Snapshot(access, JsonScalars.long(onDisk, FIELD_EXPIRES))
    }

    internal fun tokensOf(): JsonObject? =
        json.parseToJsonElement(Files.readString(authPath)).jsonObject[FIELD_TOKENS] as? JsonObject

    internal fun peerRotation(priorAccess: String?, snap: Snapshot?): RefreshOutcome? {
        if (priorAccess == null || snap == null) return null
        if (snap.access == priorAccess) return null
        // The file is held under a cross-process CredentialLock precisely because peers (another
        // splice, the official grok CLI) write it concurrently, so the window between the read that
        // produced [snap] and this stat is the contended one. Every other failure in this ladder
        // degrades to an outcome; an unguarded IOException here escaped refreshLocked() as a crash.
        return Cancellables.runCatchingCancellable { Files.getLastModifiedTime(authPath).toMillis() }
            .onFailure { log("[grok-auth] stat of $authPath failed: $it — skipping peer rotation, refreshing instead") }
            .getOrNull()
            ?.let { mtime ->
                cache = Cache(snap, mtime, clock())
                RefreshOutcome.Refreshed(Credentials.Bearer(snap.access, null))
            }
    }
}
