// NEW: Muse device OAuth wire constants, forms, and response contracts.
package splice.provider.muse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.util.Cancellables
import splice.core.util.FormEncoding
import splice.core.util.JsonScalars
import java.net.URI

public object MuseOAuthEndpoints {
    public const val CLIENT_ID: String = "1031625952748946"
    public const val DEVICE_CODE_GRANT_TYPE: String = "urn:ietf:params:oauth:grant-type:device_code"
    public const val DEVICE_AUTHORIZATION_URL: String = "https://auth.meta.com/oidc/device/authorization/"
    public const val TOKEN_URL: String = "https://auth.meta.com/oidc/device/token/"
    public const val KEY_URL: String = "https://api.meta.ai/muse-code/key"
    public const val DEFAULT_EXPIRES_IN_S: Long = 600L
    public const val DEFAULT_INTERVAL_S: Long = 5L
    public const val MIN_INTERVAL_S: Long = 1L
}

/** Parsed Muse device-authorization response. */
public data class MuseDeviceAuthorization(
    public val userCode: String,
    public val deviceCode: String,
    public val verificationUri: String,
    public val verificationUriComplete: String,
    public val expiresInS: Long,
    public val intervalS: Long,
)

/** Muse OAuth wire helpers and strict subscription-key response classification. */
public class MuseOAuth {
    private val trustedActionHosts = setOf(
        "meta.ai",
        "www.meta.ai",
        "api.meta.ai",
        "auth.meta.com",
        "meta.com",
        "www.meta.com",
    )

    public fun museDeviceAuthorizationForm(clientId: String = MuseOAuthEndpoints.CLIENT_ID): String =
        FormEncoding.formEncode("client_id" to clientId)

    public fun museTokenPollForm(
        deviceCode: String,
        clientId: String = MuseOAuthEndpoints.CLIENT_ID,
    ): String = FormEncoding.formEncode(
        "client_id" to clientId,
        "device_code" to deviceCode,
        "grant_type" to MuseOAuthEndpoints.DEVICE_CODE_GRANT_TYPE,
    )

    public fun parseMuseDeviceAuthorization(responseBody: String): MuseDeviceAuthorization {
        val obj = parseObject(responseBody) ?: JsonObject(emptyMap())
        return MuseDeviceAuthorization(
            userCode = JsonScalars.strIfString(obj["user_code"]),
            deviceCode = JsonScalars.strIfString(obj["device_code"]),
            verificationUri = JsonScalars.strIfString(obj["verification_uri"]),
            verificationUriComplete = JsonScalars.strIfString(obj["verification_uri_complete"]),
            expiresInS = JsonScalars.long(obj, "expires_in") ?: MuseOAuthEndpoints.DEFAULT_EXPIRES_IN_S,
            intervalS = maxOf(
                MuseOAuthEndpoints.MIN_INTERVAL_S,
                JsonScalars.long(obj, "interval") ?: MuseOAuthEndpoints.DEFAULT_INTERVAL_S,
            ),
        )
    }

    /** The device-token success value; token_type is tolerated and no refresh/expiry fields exist. */
    public fun parseMuseAccessToken(responseBody: String): String? =
        parseObject(responseBody)
            ?.let { JsonScalars.strIfString(it["access_token"]) }
            ?.takeIf(String::isNotBlank)

    public fun parseMuseKeyResponse(responseBody: String): MuseMintAttempt {
        val obj = parseObject(responseBody) ?: return MuseMintAttempt.Denied("malformed key response")
        val subscription = subscriptionState(obj)
            ?: return MuseMintAttempt.Denied("key response missing subscription state")
        val actionUrl = actionUrl(obj)
        val paymentBlocked = !subscription.first || subscription.second
        return if (paymentBlocked) {
            MuseMintAttempt.SubscriptionRequired(safeActionOrigin(actionUrl))
        } else {
            JsonScalars.strIfString(obj["api_key"])
                .takeIf(String::isNotBlank)
                ?.let { MuseMintAttempt.Granted(MuseSubscriptionKey(it, obj)) }
                ?: MuseMintAttempt.Denied("key response missing api_key")
        }
    }

    private fun parseObject(responseBody: String): JsonObject? =
        Cancellables.runCatchingCancellable {
            museJson.parseToJsonElement(responseBody) as? JsonObject
        }.getOrNull()

    private fun subscriptionState(obj: JsonObject): Pair<Boolean, Boolean>? =
        strictBoolean(obj, "is_subs_active")?.let { active ->
            strictBoolean(obj, "require_payment")?.let { requiresPayment -> active to requiresPayment }
        }

    private fun strictBoolean(obj: JsonObject, field: String): Boolean? {
        val value = obj[field] as? JsonPrimitive ?: return null
        return value.takeUnless(JsonPrimitive::isString)?.booleanOrNull
    }

    private fun actionUrl(obj: JsonObject): String? =
        JsonScalars.strIfString(obj["action_url"]).takeIf(String::isNotBlank)
            ?: JsonScalars.strIfString(obj["require_payment_action_url"]).takeIf(String::isNotBlank)

    public fun isPlanTierRejection(body: String): Boolean =
        body.lowercase().contains("plan limit")

    public fun isAuthFailureBody(body: String): Boolean = AUTH_FAILURE_BODY.containsMatchIn(body)

    internal fun safeActionOrigin(raw: String?): String? {
        val uri = raw?.let { Cancellables.runCatchingCancellable { URI.create(it) }.getOrNull() } ?: return null
        val host = uri.host?.lowercase() ?: return null
        val trustedHttps = uri.scheme.equals("https", ignoreCase = true) && host in trustedActionHosts
        val standardAuthority = uri.rawUserInfo == null && uri.port == -1
        return if (trustedHttps && standardAuthority) "https://$host/" else null
    }
}

internal val museJson: Json = Json { ignoreUnknownKeys = true }
private val AUTH_FAILURE_BODY = Regex(
    "unauthenticated|bad-credentials|token (is )?(invalid|expired)|" +
        "(access|oauth2?) token could not be validated",
    RegexOption.IGNORE_CASE,
)
