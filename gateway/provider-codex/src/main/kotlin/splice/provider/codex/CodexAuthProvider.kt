// PORT-OF: server/src/auth/codex-oauth.mjs runtime half @ 4ca99f7 — cached auth.json read
// (path+mtime+TTL), single-flight 401 refresh (grant_type=refresh_token, preserves other
// fields, 0600 write, cache invalidation), masked introspection. Implements the core
// RefreshableAuthProvider SPI. SEAM: token HTTP POST + clock injected for tests.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.SecureFile
import splice.core.util.runCatchingCancellable
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
        tokens?.get(FIELD_ACCESS_TOKEN)?.jsonPrimitive?.content?.let { access ->
            val accountId = tokens[FIELD_ACCOUNT_ID]?.jsonPrimitive?.content
            cache = Cache(access, accountId, mtime, now)
            Credentials.Bearer(access, accountId)
        }
    }.getOrNull()

    public fun invalidateCache() {
        cache = null
    }

    override suspend fun refresh(): Credentials? = singleFlight.run { doRefresh() }

    private suspend fun doRefresh(): Credentials? {
        val raw = runCatchingCancellable {
            json.parseToJsonElement(Files.readString(authPath)).jsonObject
        }.getOrNull() ?: return null
        val tokens = raw[FIELD_TOKENS] as? JsonObject ?: return null
        return refreshAndPersist(raw, tokens)
    }

    private suspend fun refreshAndPersist(raw: JsonObject, tokens: JsonObject): Credentials? {
        val refreshToken = tokens[FIELD_REFRESH_TOKEN]?.jsonPrimitive?.content ?: return null
        // Guard the network hop too (Node wrapped the whole read+fetch): the refreshCall param's
        // type doesn't promise "never throws", so a caller-supplied hop that throws must degrade to
        // a null refresh (→ UpstreamFailed → re-prompt), not blow through SingleFlight uncaught.
        val fresh = runCatchingCancellable { refreshCall(refreshToken) }.getOrNull() ?: return null
        return fresh.accessToken?.let { access ->
            writeSecure(authPath, mergedAuthJson(raw, tokens, fresh, access).toString())
            invalidateCache()
            readCached()
        }
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
        val present = runCatchingCancellable {
            if (!Files.exists(authPath)) return@runCatchingCancellable false
            val raw = json.parseToJsonElement(Files.readString(authPath)).jsonObject
            val tokens = raw[FIELD_TOKENS] as? JsonObject
            val hasAccess = tokens?.get(FIELD_ACCESS_TOKEN)?.jsonPrimitive?.content?.isNotEmpty() == true
            val acct = tokens?.get(FIELD_ACCOUNT_ID)?.jsonPrimitive?.content.orEmpty()
            out["account_id_masked"] =
                if (acct.isNotEmpty()) "${acct.take(MASK_KEEP)}…${acct.takeLast(MASK_KEEP)}" else ""
            raw[FIELD_LAST_REFRESH]?.jsonPrimitive?.content?.let { out[FIELD_LAST_REFRESH] = it }
            hasAccess
        }.getOrDefault(false)
        return AuthDescription(present = present, kind = KIND, fields = out)
    }

    // Atomic 0600 credential write — routes to the shared primitive (was an inline temp→chmod→move).
    private fun writeSecure(path: Path, content: String) {
        SecureFile.writeAtomic0600(path, content)
    }

    private companion object {
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
