// PORT-OF: server/src/auth/codex-oauth.mjs runtime half @ 4ca99f7 — cached auth.json read
// (path+mtime+TTL), single-flight 401 refresh (grant_type=refresh_token, preserves other
// fields, 0600 write, cache invalidation), masked introspection. Implements the core
// RefreshableAuthProvider SPI. SEAM: token HTTP POST + clock injected for tests.
// Failure visibility (discipline L1): every auth-critical Result collapse consumes the failure
// with a stderr line first — a corrupt auth file must never masquerade as "not logged in".
package splice.provider.codex

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
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import splice.spi.CredentialLock
import splice.spi.SingleFlight
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

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
    /** POST grant_type=refresh_token to the token URL; returns the parsed tokens or null. */
    private val refreshCall: suspend (refreshToken: String) -> RefreshedTokens?,
) : RefreshableAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val singleFlight = SingleFlight<Credentials?>()

    @Volatile
    private var cache: Cache? = null

    private data class Cache(val token: String, val accountId: String?, val mtimeMs: Long, val loadedAt: Long)

    override suspend fun credentials(): Credentials? = readCached()

    private fun readCached(): Credentials? = runCatchingCancellable {
        if (!Files.exists(authPath)) return@runCatchingCancellable null
        val mtime = Files.getLastModifiedTime(authPath).toMillis()
        val now = clock()
        cache?.let { c ->
            if (c.mtimeMs == mtime && (now - c.loadedAt) < authCacheMs) {
                return@runCatchingCancellable Credentials.Bearer(c.token, c.accountId)
            }
        }
        val tokens = json.parseToJsonElement(Files.readString(authPath)).jsonObject[FIELD_TOKENS] as? JsonObject
        tokens?.str(FIELD_ACCESS_TOKEN)?.let { access ->
            val accountId = tokens.str(FIELD_ACCOUNT_ID)
            cache = Cache(access, accountId, mtime, now)
            Credentials.Bearer(access, accountId)
        }
    }.onFailure {
        System.err.println("[codex-auth] failed to read $authPath: $it — treating as not logged in")
    }.getOrNull()

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
        val priorAccess = cache?.token
        return CredentialLock.withLock(authPath) { refreshLocked(priorAccess) }
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
    // it and skip the POST. Token identity — codex carries no expiry field — is the unambiguous signal.
    private fun peerRotation(priorAccess: String?, tokens: JsonObject?): RefreshOutcome? {
        val freshAccess = tokens?.str(FIELD_ACCESS_TOKEN)
        if (priorAccess == null || freshAccess == null) return null
        if (freshAccess == priorAccess) return null
        val accountId = tokens.str(FIELD_ACCOUNT_ID)
        cache = Cache(freshAccess, accountId, Files.getLastModifiedTime(authPath).toMillis(), clock())
        return RefreshOutcome.Refreshed(Credentials.Bearer(freshAccess, accountId))
    }

    private suspend fun exchangeRefreshToken(
        raw: JsonObject,
        tokens: JsonObject,
        allowRereadRetry: Boolean = true,
    ): RefreshOutcome {
        val refreshToken = tokens.str(FIELD_REFRESH_TOKEN) ?: return RefreshOutcome.NoRefreshToken
        // Guard the network hop too (Node wrapped the whole read+fetch): a thrown hop must degrade
        // to a typed outcome (→ UpstreamFailed → re-prompt), not blow through SingleFlight uncaught.
        val fresh = runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
        val access = fresh?.accessToken
        return when {
            fresh == null -> rejectedOrRetry(refreshToken, allowRereadRetry, "token endpoint returned no tokens")
            access == null ->
                rejectedOrRetry(refreshToken, allowRereadRetry, "refresh response missing access_token")
            else -> persistRotation(raw, tokens, fresh, access)
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
        val fresh = runCatchingCancellable {
            json.parseToJsonElement(Files.readString(authPath)).jsonObject
        }.getOrElse { return RefreshOutcome.Rejected(reason) }
        val newTokens = fresh[FIELD_TOKENS] as? JsonObject
        val newToken = newTokens?.str(FIELD_REFRESH_TOKEN)
        val rotated = newToken != null && newToken != usedRefreshToken
        return if (rotated && newTokens != null) {
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
        writeSecure(authPath, mergedAuthJson(raw, tokens, fresh, access).toString())
        invalidateCache()
        return readCached()?.let { RefreshOutcome.Refreshed(it) }
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
        return AuthDescription(present = present, kind = KIND, fields = out)
    }

    // Atomic 0600 credential write — routes to the shared primitive (was an inline temp→chmod→move).
    private fun writeSecure(path: Path, content: String) {
        SecureFile.writeAtomic0600(path, content)
    }

    private companion object {
        const val LOG_TAG = "codex-auth"
        const val KIND = "chatgpt-oauth"
        const val MASK_KEEP = 4
        const val FIELD_TOKENS = "tokens"
        const val FIELD_ACCESS_TOKEN = "access_token"
        const val FIELD_REFRESH_TOKEN = "refresh_token"
        const val FIELD_ID_TOKEN = "id_token"
        const val FIELD_ACCOUNT_ID = "account_id"
        const val FIELD_LAST_REFRESH = "last_refresh"
    }
}
