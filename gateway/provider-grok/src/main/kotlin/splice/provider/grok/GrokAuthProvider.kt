// NEW: xAI Grok OAuth runtime auth — the SuperGrok/X-Premium+ browser login (GrokOAuth), NOT an
// api key. Mirrors CodexAuthProvider: cached ~/.grok/auth.json read (path+mtime+TTL), single-flight
// refresh (grant_type=refresh_token, 0600 write, cache invalidation), masked introspection.
// Grok tokens carry no account id (no ChatGPT-Account-ID header), so Bearer.accountId is null.
// PROACTIVE refresh (grok-dead-head incident, 2026-07-18): xAI reports an expired token as 403
// (not 401), so the reactive 401-refresh path never fired and the head served a dead token
// until manual re-login. Like KimiAuthProvider: when the file's `expires` (ms epoch, written by
// the official grok CLI and by us) is within the proactive window, refresh BEFORE serving; a
// failed refresh on a not-yet-expired token still serves the current one.
// Failure visibility (discipline L1): every auth-critical Result collapse consumes the failure
// with a stderr line first — a corrupt auth file must never masquerade as "not logged in".
package splice.provider.grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
import splice.core.auth.credentialsOrNull
import splice.core.util.SecureFile
import splice.core.util.long
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import splice.spi.CredentialLock
import splice.spi.SingleFlight
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Parsed result of the grok token-endpoint refresh POST. */
public data class GrokRefreshedTokens(
    val accessToken: String?,
    val refreshToken: String?,
    /** Seconds until the new access token expires; null when the endpoint omits it. */
    val expiresIn: Long? = null,
)

public class GrokAuthProvider(
    private val authPath: Path,
    private val authCacheMs: Long = DEFAULT_CACHE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nowIso: () -> String = { Instant.ofEpochMilli(System.currentTimeMillis()).toString() },
    /** POST grant_type=refresh_token to auth.x.ai's token URL; returns the parsed tokens or null. */
    private val refreshCall: suspend (refreshToken: String) -> GrokRefreshedTokens?,
) : RefreshableAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val singleFlight = SingleFlight<Credentials?>()

    @Volatile
    private var cache: Cache? = null

    private data class Cache(val snapshot: Snapshot, val mtimeMs: Long, val loadedAt: Long)

    private data class Snapshot(val access: String, val expiresAtMs: Long?)

    override suspend fun credentials(): Credentials? {
        val snap = readSnapshot() ?: return null
        val current = Credentials.Bearer(snap.access, null)
        val expiresAt = snap.expiresAtMs
        // serve as-is when the file carries no expiry, or we're still outside the proactive window
        if (expiresAt == null || expiresAt - clock() >= PROACTIVE_WINDOW_MS) return current
        // proactive window: single-flight refresh; on failure serve the current token if still valid.
        val refreshed = singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG) }
        return refreshed ?: (if (clock() < expiresAt) current else null)
    }

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
        System.err.println("[grok-auth] failed to read $authPath: $it — treating as not logged in")
    }.getOrNull()

    // Fresh, uncached parse (mirrors KimiAuthProvider.parseSnapshot): the authoritative read used
    // both under readSnapshot's cache check and inside the refresh lock.
    private fun parseSnapshot(): Snapshot? {
        if (!Files.exists(authPath)) return null
        val onDisk = json.parseToJsonElement(Files.readString(authPath)).jsonObject
        val access = (onDisk[FIELD_TOKENS] as? JsonObject)?.get(FIELD_ACCESS_TOKEN).str() ?: return null
        return Snapshot(access, onDisk.long(FIELD_EXPIRES))
    }

    private fun tokensOf(): JsonObject? =
        json.parseToJsonElement(Files.readString(authPath)).jsonObject[FIELD_TOKENS] as? JsonObject

    public fun invalidateCache() {
        cache = null
    }

    override suspend fun refresh(): Credentials? =
        singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG) }

    // Sealed per-mode outcome (discipline L3): a dead refresh token, a transport blip, and a
    // corrupt file are DIFFERENT stories; credentialsOrNull is the single logging flatten.
    // Staged (read → exchange → persist), each stage owning its own failure branches.
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
        val (snap, refreshToken) = runCatchingCancellable {
            parseSnapshot() to tokensOf()?.get(FIELD_REFRESH_TOKEN).str()
        }.getOrElse { return RefreshOutcome.ReadFailed(it) }
        peerRotation(priorAccess, snap)?.let { return it }
        return if (refreshToken == null) RefreshOutcome.NoRefreshToken else exchangeRefreshToken(refreshToken)
    }

    // A peer (another process, or the official grok CLI) may have rotated the token while we waited on
    // the lock: if the freshly-read access token differs from what THIS process last served, adopt it
    // and skip the POST. Token identity — not the expiry-window heuristic — is the unambiguous signal.
    private fun peerRotation(priorAccess: String?, snap: Snapshot?): RefreshOutcome? {
        if (priorAccess == null || snap == null) return null
        if (snap.access == priorAccess) return null
        cache = Cache(snap, Files.getLastModifiedTime(authPath).toMillis(), clock())
        return RefreshOutcome.Refreshed(Credentials.Bearer(snap.access, null))
    }

    private suspend fun exchangeRefreshToken(
        refreshToken: String,
        allowRereadRetry: Boolean = true,
    ): RefreshOutcome {
        // Guard the network hop (the refreshCall param's type doesn't promise "never throws"): a
        // thrown hop must degrade to a typed outcome, not blow through SingleFlight uncaught.
        val fresh = runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
        val access = fresh?.accessToken
        return when {
            fresh == null ->
                rejectedOrRetry(refreshToken, allowRereadRetry, "token endpoint returned no rotated tokens")
            access == null ->
                rejectedOrRetry(refreshToken, allowRereadRetry, "refresh response missing access_token")
            else -> persistRotation(refreshToken, fresh, access)
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
        val newToken = runCatchingCancellable { tokensOf()?.get(FIELD_REFRESH_TOKEN).str() }
            .getOrElse { return RefreshOutcome.Rejected(reason) }
        return if (newToken != null && newToken != usedRefreshToken) {
            exchangeRefreshToken(newToken, allowRereadRetry = false)
        } else {
            RefreshOutcome.Rejected(reason)
        }
    }

    private fun persistRotation(refreshToken: String, fresh: GrokRefreshedTokens, access: String): RefreshOutcome {
        val expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S }
        writeSecure(authPath, mergedAuthJson(access, fresh.refreshToken ?: refreshToken, expiresAtMs).toString())
        invalidateCache()
        return RefreshOutcome.Refreshed(Credentials.Bearer(access, null))
    }

    // MERGE into the on-disk object — a from-scratch rewrite dropped `expires` and every field the
    // official grok CLI stores beside ours, corrupting the shared file for it (audit 2026-07-18;
    // the codex twin already merged correctly). `expires` is OVERWRITTEN when the refresh response
    // carried expires_in — keeping the old value would leave a stale past expiry that re-triggers
    // the proactive refresh on every turn.
    private fun mergedAuthJson(access: String, refresh: String, expiresAtMs: Long?): JsonObject {
        val onDisk = runCatchingCancellable {
            json.parseToJsonElement(Files.readString(authPath)).jsonObject
        }.onFailure {
            System.err.println("[grok-auth] re-read of $authPath for merge failed: $it — writing tokens-only file")
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

    override suspend fun describe(): AuthDescription {
        // ast-grep-ignore: kt-no-silent-result-collapse -- introspection display only: a read failure renders as present=false, which is the displayed truth
        val present = runCatchingCancellable {
            Files.exists(authPath) && tokensOf()?.get(FIELD_ACCESS_TOKEN) != null
        }.getOrDefault(false)
        return AuthDescription(
            present = present,
            kind = "grok-oauth",
            fields = buildMap {
                put("auth_path", authPath.toString())
                put("login", "browser")
            },
        )
    }

    // Atomic 0600 credential write — routes to the shared primitive (was an inline temp→chmod→move).
    private fun writeSecure(path: Path, content: String) {
        SecureFile.writeAtomic0600(path, content)
    }

    private companion object {
        const val LOG_TAG = "grok-auth"
        const val DEFAULT_CACHE_MS = 30_000L
        const val MS_PER_S = 1000L

        /** Refresh this long before `expires` — well inside a 6h grok token, generous vs clock skew. */
        const val PROACTIVE_WINDOW_MS = 300_000L
        const val FIELD_TOKENS = "tokens"
        const val FIELD_ACCESS_TOKEN = "access_token"
        const val FIELD_REFRESH_TOKEN = "refresh_token"
        const val FIELD_LAST_REFRESH = "last_refresh"
        const val FIELD_EXPIRES = "expires"
    }
}
