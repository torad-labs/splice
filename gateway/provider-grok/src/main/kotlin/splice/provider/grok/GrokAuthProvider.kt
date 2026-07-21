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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.RefreshAttempt
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
    /** POST grant_type=refresh_token to auth.x.ai's token URL; returns the classified attempt. */
    private val refreshCall: suspend (refreshToken: String) -> RefreshAttempt<GrokRefreshedTokens>,
) : RefreshableAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val singleFlight = SingleFlight<Credentials?>()
    private val invalidGrantLatch = InvalidGrantLatch()

    // This is the injection seam: an owned background scope, decoupled from any single request's
    // coroutine, for the G17 async-prefetch
    // tier. Mirrors SingleFlight's own internal scope (same PORT-OF pattern, SingleFlight.kt:36).
    private val prefetchScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var cache: Cache? = null

    private data class Cache(val snapshot: Snapshot, val mtimeMs: Long, val loadedAt: Long)

    private data class Snapshot(val access: String, val expiresAtMs: Long?)

    // Three tiers by remaining time-to-expiry, as a single if/else-if/else expression (not `when`,
    // not extra member functions — GrokAuthProvider is already at its detekt function-count budget):
    // outside the proactive window, serve as-is; above the stale floor, prefetch in the background
    // and serve the current token (G17); below the floor, block for a confirmed-fresh token exactly
    // as before G17 (on a failed refresh still serve the current token if it hasn't actually expired).
    override suspend fun credentials(): Credentials? {
        val snap = readSnapshot() ?: return null
        val current = Credentials.Bearer(snap.access, null)
        val expiresAt = snap.expiresAtMs
        // expiresAt is always populated now (real, or synthesized off mtime by readSnapshot — G18);
        // still outside the proactive window means serve as-is. The null branch below stays as
        // defensive-only dead code for a future caller that constructs Snapshot directly.
        return if (expiresAt == null || expiresAt - clock() >= PROACTIVE_WINDOW_MS) {
            current
        } else if (expiresAt - clock() >= STALE_FLOOR_MS) {
            // prefetch tier (G17): kick a single-flight refresh in the background, serve the CURRENT
            // token now. singleFlight still dedups concurrent entrants to one network call;
            // credentialsOrNull still owns the one logging flatten (discipline L3).
            prefetchScope.launch { singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG) } }
            current
        } else {
            // stale floor: too close to hard expiry to risk it — block for a confirmed-fresh token,
            // same as pre-G17 behavior.
            val refreshed = singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG) }
            refreshed ?: (if (clock() < expiresAt) current else null)
        }
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
        // G18: a file with no top-level `expires` (legacy shape, or a foreign CLI write that
        // stripped it) is otherwise never-expiring — synthesize a ceiling off the mtime already
        // read above, no new I/O.
        parseSnapshot()
            ?.let { it.copy(expiresAtMs = it.expiresAtMs ?: (mtime + SYNTHETIC_EXPIRY_TTL_MS)) }
            ?.also { cache = Cache(it, mtime, now) }
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
    // G15: gate on a latched confirmed invalid_grant BEFORE any file-content read or network call —
    // a dead token no longer gets re-POSTed every turn. The gate gives way the instant the file's
    // mtime changes (re-login), so a genuinely stale latch never outlives the credentials it named.
    private suspend fun doRefresh(): RefreshOutcome {
        if (!Files.exists(authPath)) return RefreshOutcome.NoCredentialsFile
        val mtime = grokAuthMtimeOrNull(authPath)
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

    // G15: InvalidGrant flows through the SAME rejectedOrRetry() G1 reread-once dance as any other
    // rejection — a "confirmed" invalid_grant (the one doRefresh() latches on) is one that survived
    // that race check, exactly matching the gap's "post-G1 re-read" requirement.
    private suspend fun exchangeRefreshToken(
        refreshToken: String,
        allowRereadRetry: Boolean = true,
    ): RefreshOutcome {
        // Guard the network hop (the refreshCall param's type doesn't promise "never throws"): a
        // thrown hop must degrade to a typed outcome, not blow through SingleFlight uncaught.
        val attempt = runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
        return when (attempt) {
            is RefreshAttempt.Granted -> {
                val access = attempt.tokens.accessToken
                if (access == null) {
                    rejectedOrRetry(refreshToken, allowRereadRetry, "refresh response missing access_token")
                } else {
                    persistRotation(refreshToken, attempt.tokens, access)
                }
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
        val mtime = grokAuthMtimeOrNull(authPath)
        return AuthDescription(
            present = present,
            kind = "grok-oauth",
            fields = buildMap {
                put("auth_path", authPath.toString())
                put("login", "browser")
                if (invalidGrantLatch.isLatched(mtime)) put("refresh_latched", INVALID_GRANT_REASON)
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

        /** Below this, block instead of prefetching: comfortably above the refreshCall's measured
         *  RTT (sub-second) and well below the 300s window, so most of the window stays non-blocking. */
        const val STALE_FLOOR_MS = 30_000L

        /** 4h ceiling synthesized for auth files with no `expires` field (legacy/foreign CLI writes,
         *  G18) — otherwise readSnapshot() would treat them as never-expiring. */
        const val SYNTHETIC_EXPIRY_TTL_MS = 4 * 60 * 60 * 1000L
        const val FIELD_TOKENS = "tokens"
        const val FIELD_ACCESS_TOKEN = "access_token"
        const val FIELD_REFRESH_TOKEN = "refresh_token"
        const val FIELD_LAST_REFRESH = "last_refresh"
        const val FIELD_EXPIRES = "expires"
    }
}

// G15: best-effort mtime probe for the invalid_grant latch gate. Top-level (not a class member) so
// GrokAuthProvider stays under the TooManyFunctions ceiling; shared by doRefresh() and describe().
// The failure is logged, not swallowed, before collapsing to null — a stat failure is "unknown",
// which InvalidGrantLatch treats as fail-open (never suppresses), NOT "file unchanged".
private fun grokAuthMtimeOrNull(authPath: Path): Long? = runCatchingCancellable {
    Files.getLastModifiedTime(authPath).toMillis()
}.onFailure {
    System.err.println("[grok-auth] failed to stat $authPath mtime: $it — invalid_grant latch check skipped")
}.getOrNull()
