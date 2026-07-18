// NEW: xAI Grok OAuth runtime auth — the SuperGrok/X-Premium+ browser login (GrokOAuth), NOT an
// api key. Mirrors CodexAuthProvider: cached ~/.grok/auth.json read (path+mtime+TTL), single-flight
// 401 refresh (grant_type=refresh_token, 0600 write, cache invalidation), masked introspection.
// Grok tokens carry no account id (no ChatGPT-Account-ID header), so Bearer.accountId is null.
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
import splice.core.util.runCatchingCancellable
import splice.spi.SingleFlight
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

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

    private fun readCached(): Credentials? = runCatchingCancellable {
        if (!Files.exists(authPath)) return@runCatchingCancellable null
        val mtime = Files.getLastModifiedTime(authPath).toMillis()
        val now = clock()
        cache?.let { c ->
            if (c.mtimeMs == mtime && (now - c.loadedAt) < authCacheMs) {
                return@runCatchingCancellable Credentials.Bearer(c.token, null)
            }
        }
        tokensOf()?.get(FIELD_ACCESS_TOKEN)?.jsonPrimitive?.content?.let { access ->
            cache = Cache(access, mtime, now)
            Credentials.Bearer(access, null)
        }
    }.getOrNull()

    private fun tokensOf(): JsonObject? =
        json.parseToJsonElement(Files.readString(authPath)).jsonObject[FIELD_TOKENS] as? JsonObject

    public fun invalidateCache() {
        cache = null
    }

    override suspend fun refresh(): Credentials? = singleFlight.run { doRefresh() }

    private suspend fun doRefresh(): Credentials? {
        val refreshToken = runCatchingCancellable { tokensOf() }.getOrNull()
            ?.get(FIELD_REFRESH_TOKEN)?.jsonPrimitive?.content ?: return null
        // Guard the network hop (the refreshCall param's type doesn't promise "never throws"): a
        // caller-supplied hop that throws must degrade to a null refresh (→ re-prompt), not blow
        // through SingleFlight uncaught. The write/reload below stays unguarded, as before.
        val fresh = runCatchingCancellable { refreshCall(refreshToken) }.getOrNull()
        val access = fresh?.accessToken ?: return null
        writeSecure(authPath, mergedAuthJson(access, fresh.refreshToken ?: refreshToken).toString())
        invalidateCache()
        return readCached()
    }

    // MERGE into the on-disk object — a from-scratch rewrite dropped `expires` and every field the
    // official grok CLI stores beside ours, corrupting the shared file for it (audit 2026-07-18;
    // the codex twin already merged correctly).
    private fun mergedAuthJson(access: String, refresh: String): JsonObject {
        val onDisk = runCatchingCancellable {
            json.parseToJsonElement(Files.readString(authPath)).jsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
        val oldTokens = onDisk[FIELD_TOKENS] as? JsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            onDisk.forEach { (k, v) -> if (k != FIELD_TOKENS && k != FIELD_LAST_REFRESH) put(k, v) }
            put(
                FIELD_TOKENS,
                buildJsonObject {
                    oldTokens.forEach { (k, v) -> if (k != FIELD_ACCESS_TOKEN && k != FIELD_REFRESH_TOKEN) put(k, v) }
                    put(FIELD_ACCESS_TOKEN, JsonPrimitive(access))
                    put(FIELD_REFRESH_TOKEN, JsonPrimitive(refresh))
                },
            )
            put(FIELD_LAST_REFRESH, JsonPrimitive(nowIso()))
        }
    }

    override suspend fun describe(): AuthDescription {
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

    private fun writeSecure(path: Path, content: String) {
        Files.createDirectories(path.parent)
        // 0600 BEFORE content, then ATOMIC move — write-then-chmod exposed the token world-readable
        // for a window, and a truncating in-place write could tear the file under a concurrent
        // reader (the exact gap OAuthLoginFlow already fixed; audit 2026-07-18).
        val tmp = Files.createTempFile(path.parent, ".auth", ".tmp")
        runCatchingCancellable {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        }
        Files.writeString(tmp, content)
        Files.move(
            tmp,
            path,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private companion object {
        const val DEFAULT_CACHE_MS = 30_000L
        const val FIELD_TOKENS = "tokens"
        const val FIELD_ACCESS_TOKEN = "access_token"
        const val FIELD_REFRESH_TOKEN = "refresh_token"
        const val FIELD_LAST_REFRESH = "last_refresh"
    }
}
