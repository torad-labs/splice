// NEW: xAI Grok OAuth runtime auth — the SuperGrok/X-Premium+ browser login (GrokOAuth), NOT an
// api key. Mirrors CodexAuthProvider: cached ~/.grok/auth.json read (path+mtime+TTL), single-flight
// 401 refresh (grant_type=refresh_token, 0600 write, cache invalidation), masked introspection.
// Grok tokens carry no account id (no ChatGPT-Account-ID header), so Bearer.accountId is null.
@file:Suppress("StringLiteralDuplication") // token/auth field names are the wire contract

package splice.provider.grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

/** Parsed result of the grok token-endpoint refresh POST. */
public data class GrokRefreshedTokens(val accessToken: String?, val refreshToken: String?)

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

    private data class Cache(val token: String, val mtimeMs: Long, val loadedAt: Long)

    override suspend fun credentials(): Credentials? = readCached()

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "ReturnCount")
    private fun readCached(): Credentials? {
        try {
            if (!Files.exists(authPath)) return null
            val mtime = Files.getLastModifiedTime(authPath).toMillis()
            val now = clock()
            cache?.let { c ->
                if (c.mtimeMs == mtime && (now - c.loadedAt) < authCacheMs) return Credentials.Bearer(c.token, null)
            }
            val access = tokensOf()?.get("access_token")?.jsonPrimitive?.content ?: return null
            cache = Cache(access, mtime, now)
            return Credentials.Bearer(access, null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
    }

    private fun tokensOf(): JsonObject? =
        json.parseToJsonElement(Files.readString(authPath)).jsonObject["tokens"] as? JsonObject

    public fun invalidateCache() {
        cache = null
    }

    override suspend fun refresh(): Credentials? = singleFlight.run { doRefresh() }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "ReturnCount", "CyclomaticComplexMethod")
    private suspend fun doRefresh(): Credentials? {
        val existing = try {
            tokensOf()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        } ?: return null
        val refreshToken = existing["refresh_token"]?.jsonPrimitive?.content ?: return null
        val fresh = try {
            refreshCall(refreshToken)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return null
        val access = fresh.accessToken ?: return null
        val next = buildJsonObject {
            put(
                "tokens",
                buildJsonObject {
                    put("access_token", JsonPrimitive(access))
                    put("refresh_token", JsonPrimitive(fresh.refreshToken ?: refreshToken))
                },
            )
            put("last_refresh", JsonPrimitive(nowIso()))
        }
        writeSecure(authPath, next.toString())
        invalidateCache()
        return readCached()
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    override suspend fun describe(): AuthDescription {
        val present = try {
            Files.exists(authPath) && tokensOf()?.get("access_token") != null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        return AuthDescription(
            present = present,
            kind = "grok-oauth",
            fields = buildMap {
                put("auth_path", authPath.toString())
                put("login", "browser")
            },
        )
    }

    private fun writeSecure(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    private companion object {
        const val DEFAULT_CACHE_MS = 30_000L
    }
}
