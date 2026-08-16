// PORT-OF: server/src/auth/codex-oauth.mjs runtime half @ pre-public-port-baseline — cached auth.json read
// (path+mtime+TTL), single-flight 401 refresh (grant_type=refresh_token, preserves other
// fields, 0600 write, cache invalidation), masked introspection. Implements the core
// RefreshableAuthProvider SPI. SEAM: token HTTP POST + clock injected for tests.
// Failure visibility (discipline L1): every auth-critical Result collapse consumes the failure
// with a stderr line first — a corrupt auth file must never masquerade as "not logged in".
// PROACTIVE refresh (grok-dead-head incident's latent codex twin, 2026-07-18): mirrors
// GrokAuthProvider's proactive-window shape, but codex's access_token is itself a JWT — the
// expiry comes from its own `exp` claim (decodeJwtClaims), not a stored `expires` field, so
// auth.json's shape stays byte-identical to the real codex CLI's. G17 two-tier as in grok/kimi:
// above the stale floor the request never waits on the refresh round trip.
package splice.provider.codex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import splice.core.auth.synthesizedExpiryMs
import splice.core.util.DaemonLog
import splice.core.util.SecureFile
import splice.core.util.long
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import splice.spi.CredentialLock
import splice.spi.SingleFlight
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val REFRESH_ERROR_SNIPPET = 160
private const val LOG_TAG = "codex-auth"
private const val KIND = "chatgpt-oauth"
private const val MASK_KEEP = 4
private const val MS_PER_S = 1000L

// Refresh this long before the JWT `exp` claim — codex reference
// (CHATGPT_ACCESS_TOKEN_REFRESH_WINDOW_MINUTES) uses 5 minutes; mirrors grok's PROACTIVE_WINDOW_MS.
private const val PROACTIVE_WINDOW_MS = 300_000L

// G17 stale floor: below this the refresh blocks the request (mirrors grok's STALE_FLOOR_MS).
private const val STALE_FLOOR_MS = 30_000L
private const val FIELD_TOKENS = "tokens"
private const val FIELD_ACCESS_TOKEN = "access_token"
private const val FIELD_REFRESH_TOKEN = "refresh_token"
private const val FIELD_ID_TOKEN = "id_token"
private const val FIELD_ACCOUNT_ID = "account_id"
private const val FIELD_LAST_REFRESH = "last_refresh"
private const val FIELD_EXP = "exp"

/** Result of the token endpoint's refresh POST (only the fields we persist). */
public data class RefreshedTokens(
    val accessToken: String?,
    val refreshToken: String?,
    val idToken: String?,
)

public class CodexAuthProvider(
    private val authPath: Path,
    private val authCacheMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nowIso: () -> String = { Instant.ofEpochMilli(System.currentTimeMillis()).toString() },
    /** POST grant_type=refresh_token to the token URL; returns the classified attempt. */
    private val refreshCall: suspend (refreshToken: String) -> RefreshAttempt<RefreshedTokens>,
    // Owned background scope for the G17 async-prefetch tier, decoupled from any single request's
    // coroutine. Injectable so the daemon can tie it to its lifecycle and tests can drain it before
    // teardown (an in-flight prefetch racing @TempDir cleanup was a CI-only flake). Mirrors GrokAuthProvider.
    private val prefetchScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    /** Daemon log sink (Main.persistentLogger): writes BOTH stderr and daemon.log, which is what
     *  /mgmt/logs tails. A bare System.err.println reaches stderr ONLY, so its line never appears in
     *  the log endpoint — the failure you most want to read is the one you cannot (wall
     *  kt-no-println, 2026-07-27). Defaults to a no-op so tests need not thread it; the daemon
     *  always injects the real sink. */
    private val log: (String) -> Unit = DaemonLog::write,
) : RefreshableAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val singleFlight = SingleFlight<Credentials?>()
    private val invalidGrantLatch = InvalidGrantLatch()

    // The JWT claim reader moved to CodexOAuth with the rest of the codex OAuth wire helpers
    // (HD-M5); this class reads `exp` and the account id through it.
    private val oauth = CodexOAuth()

    init {
        // Lifecycle ownership: when prefetchScope ends (Daemon.stop cancels probeScope), cancel the
        // shared refresh so it cannot persist a token after shutdown. Per-request cancellation is
        // unaffected — it only cancels that caller's await, never the SingleFlight scope.
        prefetchScope.coroutineContext[Job]?.invokeOnCompletion { singleFlight.close() }
    }

    @Volatile
    private var cache: Cache? = null

    // SH-01: one shape-drift log per distinct auth.json mtime, not one per cached read.
    @Volatile
    private var synthLoggedMtime: Long = -1L

    /** SH-01: the shared missing-expiry policy, logged once per distinct mtime so a drifted
     *  auth.json shape (opaque token, exp-less JWT) is visible to the operator, not just felt. */
    private fun synthesizeExpiry(mtimeMs: Long): Long {
        if (synthLoggedMtime != mtimeMs) {
            synthLoggedMtime = mtimeMs
            log(
                "[codex-auth] access token carries no decodable exp — synthesized expiry " +
                    "mtime+4h (auth.json shape drifted?)",
            )
        }
        return synthesizedExpiryMs(mtimeMs, clock())
    }

    private data class Cache(val snapshot: Snapshot, val mtimeMs: Long, val loadedAt: Long, val sizeBytes: Long)

    private data class Snapshot(val access: String, val accountId: String?, val expiresAtMs: Long?)

    // Three tiers by remaining time-to-expiry, same shape as GrokAuthProvider (G17): outside the
    // proactive window serve as-is; above the stale floor prefetch in the background and serve the
    // current token; below the floor block for a confirmed-fresh token exactly as before G17.
    override suspend fun credentials(): Credentials? {
        val snap = readSnapshot() ?: return null
        val current = Credentials.Bearer(snap.access, snap.accountId)
        val expiresAt = snap.expiresAtMs
        return if (expiresAt == null || expiresAt - clock() >= PROACTIVE_WINDOW_MS) {
            current
        } else if (expiresAt - clock() >= STALE_FLOOR_MS) {
            // prefetch tier (G17): kick a single-flight refresh in the background, serve the CURRENT
            // token now. singleFlight still dedups concurrent entrants to one network call.
            prefetchScope.launch { singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) } }
            current
        } else {
            // stale floor: too close to hard expiry to risk it — block for a confirmed-fresh token.
            val refreshed = singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) }
            refreshed ?: (if (clock() < expiresAt) current else null)
        }
    }

    private fun readSnapshot(): Snapshot? = runCatchingCancellable {
        if (!Files.exists(authPath)) return@runCatchingCancellable null
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
        tokens?.str(FIELD_ACCESS_TOKEN)?.let { access ->
            val accountId = tokens.str(FIELD_ACCOUNT_ID)
            // SH-01 (G18's codex twin): a token with no decodable exp was cached FOREVER — no
            // proactive refresh, first signal a mid-turn 401. Shared policy: synthesize mtime+TTL.
            val expiresAtMs = oauth.decodeJwtClaims(access).long(FIELD_EXP)?.let { it * MS_PER_S }
                ?: synthesizeExpiry(mtime)
            val snapshot = Snapshot(access, accountId, expiresAtMs)
            cache = Cache(snapshot, mtime, now, size)
            snapshot
        }
    }.onFailure {
        log("[codex-auth] failed to read $authPath: $it — treating as not logged in")
    }.getOrNull()

    public fun invalidateCache() {
        cache = null
    }

    override suspend fun refresh(): Credentials? =
        singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) }

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
        val mtime = codexAuthMtimeOrNull(authPath, log)
        if (invalidGrantLatch.isLatched(mtime)) return RefreshOutcome.Rejected(INVALID_GRANT_REASON)
        val priorAccess = cache?.snapshot?.access
        // AUTH-002: wire the daemon log sink so the lock's proceed-unlocked fallback is observable
        // (CredentialLock.withLock's `log` default is a silent no-op) instead of only greppable
        // in a source comment.
        val outcome = CredentialLock.withLock(authPath, log = log) { refreshLocked(priorAccess) }
        if (outcome is RefreshOutcome.Rejected && outcome.reason == INVALID_GRANT_REASON) {
            invalidGrantLatch.latch(mtime)
        }
        return outcome
    }

    // Runs holding the cross-process lock: re-read fresh, short-circuit if a peer already rotated,
    // else exchange. Split out of doRefresh so withLock's lambda stays a single call.
    private suspend fun refreshLocked(priorAccess: String?): RefreshOutcome {
        val raw = runCatchingCancellable {
            json.parseToJsonElement(Files.readString(authPath)).jsonObject
        }.getOrElse { return RefreshOutcome.ReadFailed(it) }
        val tokens = raw[FIELD_TOKENS] as? JsonObject
        peerRotation(priorAccess, tokens)?.let { return it }
        return if (tokens == null) RefreshOutcome.NoRefreshToken else exchangeRefreshToken(raw, tokens)
    }

    // A peer (another process, or the official codex CLI) may have rotated the token while we waited
    // on the lock: if the freshly-read access token differs from what THIS process last served, adopt
    // it and skip the POST. Token identity — not the `exp` claim — is the unambiguous signal.
    private fun peerRotation(priorAccess: String?, tokens: JsonObject?): RefreshOutcome? {
        val freshAccess = tokens?.str(FIELD_ACCESS_TOKEN)
        if (priorAccess == null || freshAccess == null) return null
        if (freshAccess == priorAccess) return null
        val accountId = tokens.str(FIELD_ACCOUNT_ID)
        val expiresAtMs = oauth.decodeJwtClaims(freshAccess).long(FIELD_EXP)?.let { it * MS_PER_S }
        val snapshot = Snapshot(freshAccess, accountId, expiresAtMs)
        cache = Cache(snapshot, Files.getLastModifiedTime(authPath).toMillis(), clock(), Files.size(authPath))
        return RefreshOutcome.Refreshed(Credentials.Bearer(freshAccess, accountId))
    }

    // G15: InvalidGrant flows through the SAME rejectedOrRetry() G1 reread-once dance as any other
    // rejection — a "confirmed" invalid_grant (the one doRefresh() latches on) is one that survived
    // that race check, exactly matching the gap's "post-G1 re-read" requirement.
    private suspend fun exchangeRefreshToken(
        raw: JsonObject,
        tokens: JsonObject,
        allowRereadRetry: Boolean = true,
    ): RefreshOutcome {
        val refreshToken = tokens.str(FIELD_REFRESH_TOKEN) ?: return RefreshOutcome.NoRefreshToken
        // Guard the network hop too (Node wrapped the whole read+fetch): a thrown hop must degrade
        // to a typed outcome (→ UpstreamFailed → re-prompt), not blow through SingleFlight uncaught.
        val attempt = runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
        return when (attempt) {
            is RefreshAttempt.Granted -> {
                val access = attempt.tokens.accessToken
                if (access == null) {
                    rejectedOrRetry(refreshToken, allowRereadRetry, "refresh response missing access_token")
                } else {
                    persistRotation(raw, tokens, attempt.tokens, access)
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
        val reread = runCatchingCancellable {
            json.parseToJsonElement(Files.readString(authPath)).jsonObject
        }
        val rereadFailure = reread.exceptionOrNull()
        if (rereadFailure != null) {
            val detail = rereadFailure.message.orEmpty().take(REFRESH_ERROR_SNIPPET)
            return RefreshOutcome.Rejected("$reason; credential reread failed: $detail")
        }
        val fresh = reread.getOrThrow()
        val newTokens = fresh[FIELD_TOKENS] as? JsonObject
        val rotatedToken = newTokens?.str(FIELD_REFRESH_TOKEN)?.takeUnless { it == usedRefreshToken }
        return if (newTokens != null && rotatedToken != null) {
            exchangeRefreshToken(fresh, newTokens, allowRereadRetry = false)
        } else {
            RefreshOutcome.Rejected(reason)
        }
    }

    private fun persistRotation(
        raw: JsonObject,
        tokens: JsonObject,
        fresh: RefreshedTokens,
        access: String,
    ): RefreshOutcome {
        // The endpoint already consumed the old refresh_token (Granted) by the time we get here — a
        // throwing write must degrade to a typed PersistFailed, never a raw throw through SingleFlight
        // out of credentials()/refresh(), so the not-yet-expired current token still gets served.
        runCatchingCancellable { writeSecure(authPath, mergedAuthJson(raw, tokens, fresh, access).toString()) }
            .getOrElse { return RefreshOutcome.PersistFailed("auth.json write failed: $it") }
        invalidateCache()
        return readSnapshot()?.let { RefreshOutcome.Refreshed(Credentials.Bearer(it.access, it.accountId)) }
            ?: RefreshOutcome.PersistFailed("auth.json unreadable after rotated-token write")
    }

    /** Merge the freshly refreshed tokens onto the existing auth.json, preserving every field the
     *  refresh response didn't replace (id_token/refresh_token only overwritten when present). */
    private fun mergedAuthJson(
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

    override suspend fun describe(): AuthDescription {
        val out = mutableMapOf("auth_path" to authPath.toString())
        // ast-grep-ignore: kt-no-silent-result-collapse -- introspection display only: a read failure renders as present=false, which is the displayed truth
        val present = runCatchingCancellable {
            if (!Files.exists(authPath)) return@runCatchingCancellable false
            val raw = json.parseToJsonElement(Files.readString(authPath)).jsonObject
            val tokens = raw[FIELD_TOKENS] as? JsonObject
            val hasAccess = tokens?.str(FIELD_ACCESS_TOKEN)?.isNotEmpty() == true
            val acct = tokens?.str(FIELD_ACCOUNT_ID).orEmpty()
            out["account_id_masked"] =
                if (acct.isNotEmpty()) "${acct.take(MASK_KEEP)}…${acct.takeLast(MASK_KEEP)}" else ""
            raw.str(FIELD_LAST_REFRESH)?.let { out[FIELD_LAST_REFRESH] = it }
            hasAccess
        }.getOrDefault(false)
        val mtime = codexAuthMtimeOrNull(authPath, log)
        if (invalidGrantLatch.isLatched(mtime)) out["refresh_latched"] = INVALID_GRANT_REASON
        return AuthDescription(present = present, kind = KIND, fields = out)
    }

    // Atomic 0600 credential write — routes to the shared primitive (was an inline temp→chmod→move).
    private fun writeSecure(path: Path, content: String) {
        SecureFile.writeAtomic0600(path, content)
    }

    // G15: best-effort mtime probe for the invalid_grant latch gate; shared by doRefresh() and
    // describe(). The failure is logged, not swallowed, before collapsing to null — a stat failure is
    // "unknown", which InvalidGrantLatch treats as fail-open (never suppresses), NOT "file unchanged".
    private fun codexAuthMtimeOrNull(authPath: Path, log: (String) -> Unit): Long? = runCatchingCancellable {
        Files.getLastModifiedTime(authPath).toMillis()
    }.onFailure {
        log("[codex-auth] failed to stat $authPath mtime: $it — invalid_grant latch check skipped")
    }.getOrNull()
}
