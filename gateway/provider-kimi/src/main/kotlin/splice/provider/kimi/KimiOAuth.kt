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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.util.SecureFile
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

    public fun host(env: (String) -> String?): String =
        (env("KIMI_OAUTH_HOST") ?: "https://auth.kimi.com").trimEnd('/')

    public fun deviceAuthorizationUrl(env: (String) -> String?): String =
        "${host(env)}/api/oauth/device_authorization"

    public fun tokenUrl(env: (String) -> String?): String =
        "${host(env)}/api/oauth/token"
}

// wire field names (extracted so a drift is a single-point edit, not a scatter).
private const val F_CLIENT_ID = "client_id"
private const val F_GRANT_TYPE = "grant_type"
private const val F_REFRESH_TOKEN = "refresh_token"
private const val F_ACCESS_TOKEN = "access_token"
private const val F_EXPIRES_IN = "expires_in"

/** Device-authorization request body: `client_id=<id>` — NO scope param. */
public fun kimiDeviceAuthorizationForm(clientId: String = KimiOAuthEndpoints.CLIENT_ID): String =
    formEncode(F_CLIENT_ID to clientId)

/** Token-poll body: client_id + device_code + the percent-encoded device-code grant_type. */
public fun kimiTokenPollForm(deviceCode: String, clientId: String = KimiOAuthEndpoints.CLIENT_ID): String =
    formEncode(
        F_CLIENT_ID to clientId,
        "device_code" to deviceCode,
        F_GRANT_TYPE to KimiOAuthEndpoints.DEVICE_CODE_GRANT_TYPE,
    )

/** Refresh body: `client_id=<id>&grant_type=refresh_token&refresh_token=<rt>`. */
public fun kimiRefreshForm(refreshToken: String, clientId: String = KimiOAuthEndpoints.CLIENT_ID): String =
    formEncode(
        F_CLIENT_ID to clientId,
        F_GRANT_TYPE to F_REFRESH_TOKEN,
        F_REFRESH_TOKEN to refreshToken,
    )

/** Parsed device_authorization response (expires_in default 1800; interval default 5, clamp >= 1). */
public data class KimiDeviceAuthorization(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInS: Long,
    val intervalS: Long,
)

public fun parseKimiDeviceAuthorization(responseBody: String): KimiDeviceAuthorization {
    val obj = kimiJson.parseToJsonElement(responseBody).jsonObjectOrEmpty()
    return KimiDeviceAuthorization(
        userCode = obj.kimiString("user_code").orEmpty(),
        deviceCode = obj.kimiString("device_code").orEmpty(),
        verificationUri = obj.kimiString("verification_uri").orEmpty(),
        verificationUriComplete = obj.kimiString("verification_uri_complete").orEmpty(),
        expiresInS = obj.kimiLong("expires_in") ?: KimiOAuthEndpoints.DEFAULT_EXPIRES_IN_S,
        intervalS = maxOf(
            KimiOAuthEndpoints.MIN_INTERVAL_S,
            obj.kimiLong("interval") ?: KimiOAuthEndpoints.DEFAULT_INTERVAL_S,
        ),
    )
}

/**
 * Build the flat kimi-cli-compatible auth-file JSON from a token-endpoint response. access_token,
 * refresh_token and expires_in are REQUIRED — a missing field is a hard error (rotation is
 * mandatory, so refresh_token must always be present). `scope` is persisted verbatim.
 */
public fun kimiAuthJsonFromTokenResponse(responseBody: String, nowMs: Long): JsonObject {
    val obj = kimiJson.parseToJsonElement(responseBody).jsonObjectOrEmpty()
    val tokens = KimiRefreshedTokens(
        accessToken = obj.kimiString(F_ACCESS_TOKEN) ?: error("kimi token response missing access_token"),
        refreshToken = obj.kimiString(F_REFRESH_TOKEN) ?: error("kimi token response missing refresh_token"),
        expiresIn = obj.kimiLong(F_EXPIRES_IN) ?: error("kimi token response missing expires_in"),
        scope = obj.kimiString("scope").orEmpty(),
        tokenType = obj.kimiString("token_type") ?: "Bearer",
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
 * Provided for future 401 classification (the current single-refresh-then-fail 401 hook is an
 * accepted simplification).
 */
public fun isPlanTierRejection(body: String): Boolean {
    val lower = body.lowercase()
    return lower.contains("current subscription does not have access") ||
        lower.contains("supports only kimi-k3 up to 256k")
}

internal const val MS_PER_S: Long = 1000

internal val kimiJson: Json = Json { ignoreUnknownKeys = true }

// byte-masking is inherent to RFC3986 encoding: 0xFF keeps the low byte, 0x80 is the ASCII ceiling.
private const val BYTE_MASK = 0xFF
private const val ASCII_LIMIT = 0x80

internal fun formEncode(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("&") { (k, v) -> "$k=${kimiPercentEncode(v)}" }

private fun kimiPercentEncode(value: String): String = buildString {
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and BYTE_MASK
        val ch = c.toChar()
        val unreserved = ch.isLetterOrDigit() && c < ASCII_LIMIT
        if (unreserved || ch in "-_.~") append(ch) else append("%%%02X".format(c))
    }
}

// JsonNull IS a JsonPrimitive with content "null"; every string extraction must filter it.
internal fun JsonObject.kimiString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.kimiLong(key: String): Long? = kimiString(key)?.toLongOrNull()

internal fun JsonElement.jsonObjectOrEmpty(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())

// Atomic 0600 write (auth + device_id files) — routes to the shared primitive. The old body here
// was write-then-chmod, which left the token world-readable for a window and could tear under a
// concurrent reader (the exact gap the other providers' comments call out); SecureFile closes it.
internal fun writeSecure(path: Path, content: String) {
    SecureFile.writeAtomic0600(path, content)
}
