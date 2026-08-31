// NEW: Kimi (Moonshot) OAuth building blocks — RFC 8628 device flow against auth.kimi.com, the
// login the official kimi-cli / kimi-code CLIs drive (wire contract verified byte-for-byte across
// four reference implementations, 2026-07-18). Pinned here so a constant drift (client id, host,
// endpoints, grant-type encoding, auth-file field names) is a test failure, not a silent broken
// login. Invariants: the client id is public (no secret exists — reused so no separate kimi binary
// is needed); the device-code grant_type is percent-encoded (colons → %3A); the persisted auth
// file is FLAT snake_case (kimi-cli-compatible so splice interops with the official CLIs); `scope`
// is OPAQUE — persist verbatim, never branch on it. JsonNull-safe string extraction throughout
// (kotlinx JsonNull IS a JsonPrimitive with content "null").
package splice.provider.kimi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.FormEncoding
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

public object KimiOAuthEndpoints {
    // public, shared with Moonshot's own CLIs — reused verbatim (no secret exists).
    public const val CLIENT_ID: String = "17e5f671-d194-4dfb-9706-5516cb48c098"

    // device-code grant name — colons MUST be percent-encoded on the wire (see formEncode).
    public const val DEVICE_CODE_GRANT_TYPE: String = "urn:ietf:params:oauth:grant-type:device_code"

    // device_authorization defaults when the response omits the field.
    public const val DEFAULT_EXPIRES_IN_S: Long = 1800
    public const val DEFAULT_INTERVAL_S: Long = 5
    public const val MIN_INTERVAL_S: Long = 1

    public fun host(env: EnvReader): String =
        (env("KIMI_OAUTH_HOST") ?: "https://auth.kimi.com").trimEnd('/')

    public fun deviceAuthorizationUrl(env: EnvReader): String =
        "${host(env)}/api/oauth/device_authorization"

    public fun tokenUrl(env: EnvReader): String =
        "${host(env)}/api/oauth/token"
}

// wire field names (extracted so a drift is a single-point edit, not a scatter).
private const val F_CLIENT_ID = "client_id"
private const val F_GRANT_TYPE = "grant_type"
private const val F_REFRESH_TOKEN = "refresh_token"
private const val F_ACCESS_TOKEN = "access_token"
private const val F_EXPIRES_IN = "expires_in"

/** Parsed device_authorization response (expires_in default 1800; interval default 5, clamp >= 1). */
public data class KimiDeviceAuthorization(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInS: Long,
    val intervalS: Long,
)

/** The kimi device-flow wire builders and response parsers. Stateless — collaborators construct one. */
public class KimiOAuth {

    /** Device-authorization request body: `client_id=<id>` — NO scope param. */
    public fun kimiDeviceAuthorizationForm(clientId: String = KimiOAuthEndpoints.CLIENT_ID): String =
        FormEncoding.formEncode(F_CLIENT_ID to clientId)

    /** Token-poll body: client_id + device_code + the percent-encoded device-code grant_type. */
    public fun kimiTokenPollForm(deviceCode: String, clientId: String = KimiOAuthEndpoints.CLIENT_ID): String =
        FormEncoding.formEncode(
            F_CLIENT_ID to clientId,
            "device_code" to deviceCode,
            F_GRANT_TYPE to KimiOAuthEndpoints.DEVICE_CODE_GRANT_TYPE,
        )

    /** Refresh body: `client_id=<id>&grant_type=refresh_token&refresh_token=<rt>`. */
    public fun kimiRefreshForm(refreshToken: String, clientId: String = KimiOAuthEndpoints.CLIENT_ID): String =
        FormEncoding.formEncode(
            F_CLIENT_ID to clientId,
            F_GRANT_TYPE to F_REFRESH_TOKEN,
            F_REFRESH_TOKEN to refreshToken,
        )

    public fun parseKimiDeviceAuthorization(responseBody: String): KimiDeviceAuthorization {
        val obj = jsonObjectOrEmpty(kimiJson.parseToJsonElement(responseBody))
        return KimiDeviceAuthorization(
            userCode = JsonScalars.str(obj, "user_code").orEmpty(),
            deviceCode = JsonScalars.str(obj, "device_code").orEmpty(),
            verificationUri = JsonScalars.str(obj, "verification_uri").orEmpty(),
            verificationUriComplete = JsonScalars.str(obj, "verification_uri_complete").orEmpty(),
            expiresInS = JsonScalars.long(obj, "expires_in") ?: KimiOAuthEndpoints.DEFAULT_EXPIRES_IN_S,
            intervalS = maxOf(
                KimiOAuthEndpoints.MIN_INTERVAL_S,
                JsonScalars.long(obj, "interval") ?: KimiOAuthEndpoints.DEFAULT_INTERVAL_S,
            ),
        )
    }

    /**
     * Build the flat kimi-cli-compatible auth-file JSON from a token-endpoint response. access_token,
     * refresh_token and expires_in are REQUIRED — a missing field is a hard error (rotation is
     * mandatory, so refresh_token must always be present). `scope` is persisted verbatim.
     */
    public fun kimiAuthJsonFromTokenResponse(responseBody: String, nowMs: Long): JsonObject {
        val obj = jsonObjectOrEmpty(kimiJson.parseToJsonElement(responseBody))
        val tokens = KimiRefreshedTokens(
            accessToken = JsonScalars.str(obj, F_ACCESS_TOKEN) ?: error("kimi token response missing access_token"),
            refreshToken = JsonScalars.str(obj, F_REFRESH_TOKEN)
                ?: error("kimi token response missing refresh_token"),
            expiresIn = JsonScalars.long(obj, F_EXPIRES_IN) ?: error("kimi token response missing expires_in"),
            scope = JsonScalars.str(obj, "scope").orEmpty(),
            tokenType = JsonScalars.str(obj, "token_type") ?: "Bearer",
        )
        return kimiAuthJson(tokens, nowMs)
    }

    /** The flat auth-file shape (expires_at = now/1000 + expires_in, unix SECONDS). */
    internal fun kimiAuthJson(tokens: KimiRefreshedTokens, nowMs: Long): JsonObject = buildJsonObject {
        put(F_ACCESS_TOKEN, JsonPrimitive(tokens.accessToken))
        put(F_REFRESH_TOKEN, JsonPrimitive(tokens.refreshToken))
        put("expires_at", JsonPrimitive(nowMs / MS_PER_S + tokens.expiresIn))
        put("scope", JsonPrimitive(tokens.scope))
        put("token_type", JsonPrimitive(tokens.tokenType))
        put(F_EXPIRES_IN, JsonPrimitive(tokens.expiresIn))
    }

    /**
     * Plan-tier 401s are entitlement rejections, NOT token expiry — a refresh will not fix them.
     * KimiAuthProvider uses this to veto the transport's otherwise-correct single refresh on 401.
     */
    public fun isPlanTierRejection(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("current subscription does not have access") ||
            lower.contains("supports only kimi-k3 up to 256k")
    }

    // JsonNull IS a JsonPrimitive with content "null"; every string extraction must filter it.
    internal fun jsonObjectOrEmpty(el: JsonElement): JsonObject =
        el as? JsonObject ?: JsonObject(emptyMap())

    internal fun parseSnapshot(
        authPath: Path,
        synthesizeExpiry: KimiAuthStore.SynthesizeExpiry,
    ): KimiAuthStore.Snapshot? {
        // DR-59: the read IS the absence probe (the old exists() pre-gate read an inaccessible
        // file as logged-out). Genuine absence returns null; anything else throws into the
        // caller's wrapper (readSnapshot's classified null, the exchange paths' ReadFailed).
        val raw = Cancellables.runCatchingCancellable { Files.readString(authPath) }
            .getOrElse { failure ->
                val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                    !Files.exists(authPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if (genuinelyAbsent) return null
                throw failure
            }
        val obj = jsonObjectOrEmpty(kimiJson.parseToJsonElement(raw))
        val access = JsonScalars.str(obj, "access_token") ?: return null
        return KimiAuthStore.Snapshot(
            access = access,
            refresh = JsonScalars.str(obj, "refresh_token"),
            expiresAtS = JsonScalars.long(obj, "expires_at")
                ?: synthesizeExpiry(Files.getLastModifiedTime(authPath).toMillis()),
            expiresInS = JsonScalars.long(obj, "expires_in") ?: 0L,
        )
    }

    // G15: best-effort mtime probe for the invalid_grant latch gate; shared by doRefresh() and
    // describe(). The failure is logged, not swallowed, before collapsing to null — a stat failure is
    // "unknown", which InvalidGrantLatch treats as fail-open (never suppresses), NOT "file unchanged".
    internal fun kimiAuthMtimeOrNull(authPath: Path, log: LogSink): Long? =
        Cancellables.runCatchingCancellable {
            Files.getLastModifiedTime(authPath).toMillis()
        }.onFailure {
            log("[kimi-auth] failed to stat $authPath mtime: $it — invalid_grant latch check skipped")
        }.getOrNull()
}

internal const val MS_PER_S: Long = 1000

// FILE SCOPE ON PURPOSE: one configured Json parser shared by every call. As a member it would be
// rebuilt per KimiOAuth construction, and the device-flow callers construct one per poll tick.
internal val kimiJson: Json = Json { ignoreUnknownKeys = true }
