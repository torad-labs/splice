// NEW: V4-14 Muse OAuth and subscription-key wire contract.
package muse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.muse.MuseMintAttempt
import splice.provider.muse.MuseOAuth
import splice.provider.muse.MuseOAuthEndpoints

class MuseOAuthTest {

    private val oauth = MuseOAuth()

    @Test
    fun `endpoints and client identity match the official Muse launcher`() {
        assertEquals("1031625952748946", MuseOAuthEndpoints.CLIENT_ID)
        assertEquals(
            "urn:ietf:params:oauth:grant-type:device_code",
            MuseOAuthEndpoints.DEVICE_CODE_GRANT_TYPE,
        )
        assertEquals(
            "https://auth.meta.com/oidc/device/authorization/",
            MuseOAuthEndpoints.DEVICE_AUTHORIZATION_URL,
        )
        assertEquals(
            "https://auth.meta.com/oidc/device/token/",
            MuseOAuthEndpoints.TOKEN_URL,
        )
        assertEquals(
            "https://api.meta.ai/muse-code/key",
            MuseOAuthEndpoints.KEY_URL,
        )
    }

    @Test
    fun `unparseable device authorization is a named error`() {
        val err = assertThrows(IllegalArgumentException::class.java) {
            oauth.parseMuseDeviceAuthorization("not-json")
        }
        assertTrue(err.message?.contains("was not JSON") == true, err.message)
    }

    @Test
    fun `empty device authorization codes are a named error`() {
        val err = assertThrows(IllegalArgumentException::class.java) {
            oauth.parseMuseDeviceAuthorization("{}")
        }
        assertTrue(err.message?.contains("missing user_code or device_code") == true, err.message)
    }

    @Test
    fun `login parse of a body without expires_in uses the 600s default`() {
        assertEquals(
            MuseOAuthEndpoints.DEFAULT_EXPIRES_IN_S,
            oauth.parseMuseDeviceAuthorization("""{"user_code":"A","device_code":"B"}""").expiresInS,
        )
        assertEquals(600L, MuseOAuthEndpoints.DEFAULT_EXPIRES_IN_S)
    }

    @Test
    fun `device forms are client-only then the encoded device grant`() {
        assertEquals(
            "client_id=${MuseOAuthEndpoints.CLIENT_ID}",
            oauth.museDeviceAuthorizationForm(),
        )
        assertFalse(oauth.museDeviceAuthorizationForm().contains("scope"))
        assertEquals(
            "client_id=${MuseOAuthEndpoints.CLIENT_ID}&device_code=DEV123&" +
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
            oauth.museTokenPollForm("DEV123"),
        )
    }

    @Test
    fun `device authorization response uses Muse defaults and clamps the interval`() {
        val full = oauth.parseMuseDeviceAuthorization(
            """{"user_code":"ABCD-EFGH","device_code":"device-code",
                "verification_uri":"https://auth.meta.com/oauth/device/",
                "verification_uri_complete":"","expires_in":600,"interval":5}""",
        )
        assertEquals("ABCD-EFGH", full.userCode)
        assertEquals("device-code", full.deviceCode)
        assertEquals("https://auth.meta.com/oauth/device/", full.verificationUri)
        assertEquals("", full.verificationUriComplete)
        assertEquals(600L, full.expiresInS)
        assertEquals(5L, full.intervalS)

        val defaults = oauth.parseMuseDeviceAuthorization(
            """{"user_code":"A","device_code":"B"}""",
        )
        assertEquals(600L, defaults.expiresInS)
        assertEquals(5L, defaults.intervalS)
        assertEquals(
            1L,
            oauth.parseMuseDeviceAuthorization(
                """{"user_code":"A","device_code":"B","interval":0}""",
            ).intervalS,
        )
    }

    @Test
    fun `device token response requires only a strict nonblank access token`() {
        assertEquals(
            "account-token",
            oauth.parseMuseAccessToken("""{"access_token":"account-token","token_type":"bearer"}"""),
        )
        listOf(
            "{}",
            """{"access_token":null}""",
            """{"access_token":123}""",
            """{"access_token":""}""",
            """{"access_token":"   "}""",
        ).forEach { body -> assertNull(oauth.parseMuseAccessToken(body), body) }
    }

    @Test
    fun `active subscription grants a nonblank key and retains response metadata`() {
        val attempt = oauth.parseMuseKeyResponse(ACTIVE_RESPONSE)
        assertTrue(attempt is MuseMintAttempt.Granted)
        val granted = attempt as MuseMintAttempt.Granted
        assertEquals("muse-key", granted.key.apiKey)
        assertEquals(
            "42",
            granted.key.fields["user_id"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "https://redirect.invalid/steal",
            granted.key.fields["base_url"]?.jsonPrimitive?.content,
        )
        val usage = granted.key.fields["subs_usage"]?.jsonObject
        assertEquals(
            "12",
            usage?.get("weekly")?.jsonObject?.get("used_percent")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `inactive and payment-required responses win before api key validation`() {
        val inactive = oauth.parseMuseKeyResponse(
            """{"is_subs_active":false,"require_payment":false,
                "action_url":"https://www.meta.ai/muse-code/subscribe?opaque=secret#fragment"}""",
        )
        assertTrue(inactive is MuseMintAttempt.SubscriptionRequired)
        assertEquals(
            "https://www.meta.ai/",
            (inactive as MuseMintAttempt.SubscriptionRequired).actionUrl,
        )

        val payment = oauth.parseMuseKeyResponse(
            """{"is_subs_active":true,"require_payment":true,
                "action_url":"https://auth.meta.com/pay/private-path"}""",
        )
        assertTrue(payment is MuseMintAttempt.SubscriptionRequired)
        assertEquals(
            "https://auth.meta.com/",
            (payment as MuseMintAttempt.SubscriptionRequired).actionUrl,
        )

        val actionOnly = oauth.parseMuseKeyResponse(
            """{"api_key":"key","is_subs_active":true,"require_payment":false,
                "action_url":"https://meta.com/subscribe/private"}""",
        )
        assertTrue(actionOnly is MuseMintAttempt.Granted)
        assertEquals("key", (actionOnly as MuseMintAttempt.Granted).key.apiKey)

        val paymentActionOnly = oauth.parseMuseKeyResponse(
            """{"api_key":"key","is_subs_active":true,"require_payment":false,
                "require_payment_action_url":"https://www.meta.com/subscribe/private"}""",
        )
        assertTrue(paymentActionOnly is MuseMintAttempt.Granted)
        assertEquals("key", (paymentActionOnly as MuseMintAttempt.Granted).key.apiKey)
    }

    @Test
    fun `subscription flags are strict JSON booleans and fail closed`() {
        listOf(
            """{"api_key":"key","require_payment":false}""",
            """{"api_key":"key","is_subs_active":true}""",
            """{"api_key":"key","is_subs_active":"true","require_payment":false}""",
            """{"api_key":"key","is_subs_active":true,"require_payment":"false"}""",
            """{"api_key":"key","is_subs_active":null,"require_payment":false}""",
        ).forEach { body ->
            assertTrue(
                oauth.parseMuseKeyResponse(body) is MuseMintAttempt.Denied,
                body,
            )
        }
    }

    @Test
    fun `active response requires a strict nonblank string api key`() {
        listOf(
            """{"is_subs_active":true,"require_payment":false}""",
            """{"api_key":"","is_subs_active":true,"require_payment":false}""",
            """{"api_key":"   ","is_subs_active":true,"require_payment":false}""",
            """{"api_key":123,"is_subs_active":true,"require_payment":false}""",
            """{"api_key":null,"is_subs_active":true,"require_payment":false}""",
        ).forEach { body ->
            assertTrue(
                oauth.parseMuseKeyResponse(body) is MuseMintAttempt.Denied,
                body,
            )
        }
    }

    @Test
    fun `action URL keeps only a trusted HTTPS Meta origin`() {
        val bodies = mapOf(
            "https://meta.ai/private/path?token=secret#fragment" to "https://meta.ai/",
            "https://www.meta.ai/subscribe" to "https://www.meta.ai/",
            "https://api.meta.ai/subscribe" to "https://api.meta.ai/",
            "https://auth.meta.com/pay" to "https://auth.meta.com/",
            "https://meta.com/pay" to "https://meta.com/",
            "https://www.meta.com/pay" to "https://www.meta.com/",
            "https://subdomain.meta.com/anything" to null,
            "https://subdomain.meta.ai/anything" to null,
            "http://meta.ai/pay" to null,
            "https://user:pass@meta.ai/pay" to null,
            "https://meta.ai:8443/pay" to null,
            "https://evilmeta.ai/pay" to null,
            "https://example.com/pay" to null,
            "not a URL" to null,
        )
        bodies.forEach { (url, expected) ->
            val attempt = oauth.parseMuseKeyResponse(
                """{"is_subs_active":false,"require_payment":false,"action_url":"$url"}""",
            )
            assertTrue(attempt is MuseMintAttempt.SubscriptionRequired)
            assertEquals(
                expected,
                (attempt as MuseMintAttempt.SubscriptionRequired).actionUrl,
                url,
            )
        }
        val absent = oauth.parseMuseKeyResponse(
            """{"is_subs_active":false,"require_payment":false}""",
        ) as MuseMintAttempt.SubscriptionRequired
        assertNull(absent.actionUrl)
    }

    @Test
    fun `plan-tier rejection is a muse-only veto and is not an auth-body failure`() {
        val rules = splice.spi.FailureRules()
        assertTrue(oauth.isPlanTierRejection("plan limit exceeded"))
        assertFalse(oauth.isPlanTierRejection("unauthenticated:bad-credentials"))
        AUTH_BODY_CORPUS.forEach { body ->
            assertEquals(rules.isAuthFailureBody(body), oauth.isAuthFailureBody(body), body)
        }
    }
}

private val AUTH_BODY_CORPUS = listOf(
    "unauthenticated",
    "unauthenticated:bad-credentials",
    "bad-credentials",
    "token invalid",
    "token is invalid",
    "token expired",
    "token is expired",
    "access token could not be validated",
    "oauth token could not be validated",
    "oauth2 token could not be validated",
    "",
    "plan limit exceeded",
    "permission denied",
    "quota exceeded",
    "tokens invalid",
)

private val ACTIVE_RESPONSE = Json.parseToJsonElement(
    """{"api_key":"muse-key","is_subs_active":true,"subs_tier_id":"pro",
        "subs_tier_name":"Muse Pro","user_id":"42","user_email":"operator@example.test",
        "base_url":"https://redirect.invalid/steal","has_payment_method":true,"can_subscribe":true,
        "require_payment":false,"action_url":null,
        "subs_usage":{"window":{"used_percent":8,"resets_at":100,"window_duration_mins":300},
        "weekly":{"used_percent":12,"resets_at":200}}}""",
).jsonObject.toString()
