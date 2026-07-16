// PORT-OF: server/src/auth/codex-oauth.mjs runtime half @ 4ca99f7 — cached auth.json read
// (path+mtime+TTL), single-flight 401 refresh (grant_type=refresh_token, preserves other
// fields, 0600 write, cache invalidation), masked introspection. Implements the core
// RefreshableAuthProvider SPI. SEAM: token HTTP POST + clock injected for tests.
@file:Suppress("StringLiteralDuplication") // token/auth field names are the wire contract

package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.spi.SingleFlight
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.CancellationException

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

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "ReturnCount")
    private fun readCached(): Credentials? {
        try {
            if (!Files.exists(authPath)) return null
            val mtime = Files.getLastModifiedTime(authPath).toMillis()
            val now = clock()
            cache?.let { c ->
                if (c.mtimeMs == mtime && (now - c.loadedAt) < authCacheMs) {
                    return Credentials.Bearer(c.token, c.accountId)
                }
            }
            val tokens = json.parseToJsonElement(Files.readString(authPath)).jsonObject["tokens"] as? JsonObject
            val access = tokens?.get("access_token")?.jsonPrimitive?.content ?: return null
            val accountId = tokens["account_id"]?.jsonPrimitive?.content
            cache = Cache(access, accountId, mtime, now)
            return Credentials.Bearer(access, accountId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
    }

    public fun invalidateCache() {
        cache = null
    }

    override suspend fun refresh(): Credentials? = singleFlight.run { doRefresh() }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "ReturnCount", "CyclomaticComplexMethod")
    private suspend fun doRefresh(): Credentials? {
        val raw = try {
            json.parseToJsonElement(Files.readString(authPath)).jsonObject
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        val tokens = raw["tokens"] as? JsonObject ?: return null
        val refreshToken = tokens["refresh_token"]?.jsonPrimitive?.content ?: return null
        // Guard the network hop too (Node wrapped the whole read+fetch): the refreshCall param's
        // type doesn't promise "never throws", so a caller-supplied hop that throws must degrade to
        // a null refresh (→ UpstreamFailed → re-prompt), not blow through SingleFlight uncaught.
        val fresh = try {
            refreshCall(refreshToken)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return null
        val access = fresh.accessToken ?: return null

        val nextTokens = kotlinx.serialization.json.buildJsonObject {
            tokens.forEach { (k, v) -> put(k, v) }
            put("access_token", kotlinx.serialization.json.JsonPrimitive(access))
            fresh.refreshToken?.let { put("refresh_token", kotlinx.serialization.json.JsonPrimitive(it)) }
            fresh.idToken?.let { put("id_token", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        val next = kotlinx.serialization.json.buildJsonObject {
            raw.forEach { (k, v) -> if (k != "tokens" && k != "last_refresh") put(k, v) }
            put("tokens", nextTokens)
            put("last_refresh", kotlinx.serialization.json.JsonPrimitive(nowIso()))
        }
        writeSecure(authPath, next.toString())
        invalidateCache()
        return readCached()
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    override suspend fun describe(): AuthDescription {
        val out = mutableMapOf("auth_path" to authPath.toString())
        try {
            if (!Files.exists(authPath)) return AuthDescription(present = false, kind = KIND, fields = out)
            val raw = json.parseToJsonElement(Files.readString(authPath)).jsonObject
            val tokens = raw["tokens"] as? JsonObject
            val present = tokens?.get("access_token")?.jsonPrimitive?.content?.isNotEmpty() == true
            val acct = tokens?.get("account_id")?.jsonPrimitive?.content.orEmpty()
            out["account_id_masked"] =
                if (acct.isNotEmpty()) "${acct.take(MASK_KEEP)}…${acct.takeLast(MASK_KEEP)}" else ""
            raw["last_refresh"]?.jsonPrimitive?.content?.let { out["last_refresh"] = it }
            return AuthDescription(present = present, kind = KIND, fields = out)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return AuthDescription(present = false, kind = KIND, fields = out)
        }
    }

    private fun writeSecure(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    private companion object {
        const val KIND = "chatgpt-oauth"
        const val MASK_KEEP = 4
    }
}
