// NEW: kimi OAuth wire-contract pins — device flow endpoints, the exact form bodies (incl. the
// percent-encoded device-code grant_type), device-authorization parse defaults + interval clamp,
// the flat auth-file mapping (field-for-field, rotation-required), and plan-tier 401 classification.
// A constant drift here is a test failure, not a silent broken login.
package kimi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.kimi.KimiOAuthEndpoints
import splice.provider.kimi.isPlanTierRejection
import splice.provider.kimi.kimiAuthJsonFromTokenResponse
import splice.provider.kimi.kimiDeviceAuthorizationForm
import splice.provider.kimi.kimiRefreshForm
import splice.provider.kimi.kimiTokenPollForm
import splice.provider.kimi.parseKimiDeviceAuthorization

class KimiOAuthTest {

    private val noEnv: (String) -> String? = { null }
    private val cid = "17e5f671-d194-4dfb-9706-5516cb48c098"

    @Test
    fun `endpoints match the kimi CLI`() {
        assertEquals(cid, KimiOAuthEndpoints.CLIENT_ID)
        assertEquals("https://auth.kimi.com", KimiOAuthEndpoints.host(noEnv))
        assertEquals(
            "https://auth.kimi.com/api/oauth/device_authorization",
            KimiOAuthEndpoints.deviceAuthorizationUrl(noEnv),
        )
        assertEquals("https://auth.kimi.com/api/oauth/token", KimiOAuthEndpoints.tokenUrl(noEnv))
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", KimiOAuthEndpoints.DEVICE_CODE_GRANT_TYPE)
    }

    @Test
    fun `host is env-overridable and trimmed`() {
        val env: (String) -> String? = { if (it == "KIMI_OAUTH_HOST") "https://auth.example.test/" else null }
        assertEquals("https://auth.example.test", KimiOAuthEndpoints.host(env))
        assertEquals("https://auth.example.test/api/oauth/token", KimiOAuthEndpoints.tokenUrl(env))
    }

    @Test
    fun `device authorization form is client_id only with no scope`() {
        assertEquals("client_id=$cid", kimiDeviceAuthorizationForm())
        assertFalse(kimiDeviceAuthorizationForm().contains("scope"))
    }

    @Test
    fun `token poll form carries client_id, device_code and percent-encoded grant_type`() {
        assertEquals(
            "client_id=$cid&device_code=DEV123&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
            kimiTokenPollForm("DEV123"),
        )
    }

    @Test
    fun `refresh form carries client_id, grant and refresh_token`() {
        assertEquals(
            "client_id=$cid&grant_type=refresh_token&refresh_token=my-rt",
            kimiRefreshForm("my-rt"),
        )
    }

    @Test
    fun `device authorization parse applies defaults and clamps interval`() {
        val full = parseKimiDeviceAuthorization(
            """{"user_code":"WXYZ-1234","device_code":"dev-abc",
                "verification_uri":"https://kimi.com/device",
                "verification_uri_complete":"https://kimi.com/device?code=WXYZ-1234",
                "expires_in":600,"interval":8}""",
        )
        assertEquals("WXYZ-1234", full.userCode)
        assertEquals("dev-abc", full.deviceCode)
        assertEquals("https://kimi.com/device", full.verificationUri)
        assertEquals("https://kimi.com/device?code=WXYZ-1234", full.verificationUriComplete)
        assertEquals(600L, full.expiresInS)
        assertEquals(8L, full.intervalS)

        // defaults: expires_in -> 1800, interval -> 5
        val defaults = parseKimiDeviceAuthorization("""{"user_code":"A","device_code":"b"}""")
        assertEquals(1800L, defaults.expiresInS)
        assertEquals(5L, defaults.intervalS)

        // interval clamp: 0 -> 1
        val clamped = parseKimiDeviceAuthorization("""{"user_code":"A","device_code":"b","interval":0}""")
        assertEquals(1L, clamped.intervalS)
    }

    @Test
    fun `token response maps to the flat kimi-cli auth file field-for-field`() {
        val body =
            """{"access_token":"at","refresh_token":"rt","expires_in":3600,"scope":"coding","token_type":"Bearer"}"""
        val obj = Json.parseToJsonElement(kimiAuthJsonFromTokenResponse(body, nowMs = 5000L).toString()).jsonObject
        assertEquals("at", obj["access_token"]?.jsonPrimitive?.content)
        assertEquals("rt", obj["refresh_token"]?.jsonPrimitive?.content)
        assertEquals("3605", obj["expires_at"]?.jsonPrimitive?.content) // 5000/1000 + 3600
        assertEquals("coding", obj["scope"]?.jsonPrimitive?.content)
        assertEquals("Bearer", obj["token_type"]?.jsonPrimitive?.content)
        assertEquals("3600", obj["expires_in"]?.jsonPrimitive?.content)
    }

    @Test
    fun `scope defaults empty and token_type defaults Bearer when absent`() {
        val body = """{"access_token":"at","refresh_token":"rt","expires_in":100}"""
        val obj = Json.parseToJsonElement(kimiAuthJsonFromTokenResponse(body, nowMs = 0L).toString()).jsonObject
        assertEquals("", obj["scope"]?.jsonPrimitive?.content)
        assertEquals("Bearer", obj["token_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `token response missing refresh_token is a hard error (rotation is mandatory)`() {
        assertThrows(IllegalStateException::class.java) {
            kimiAuthJsonFromTokenResponse("""{"access_token":"at","expires_in":3600}""", nowMs = 0L)
        }
    }

    @Test
    fun `token response missing access_token or expires_in is a hard error`() {
        assertThrows(IllegalStateException::class.java) {
            kimiAuthJsonFromTokenResponse("""{"refresh_token":"rt","expires_in":3600}""", nowMs = 0L)
        }
        assertThrows(IllegalStateException::class.java) {
            kimiAuthJsonFromTokenResponse("""{"access_token":"at","refresh_token":"rt"}""", nowMs = 0L)
        }
    }

    @Test
    fun `plan-tier rejection recognises entitlement bodies and rejects token-expiry bodies`() {
        assertTrue(isPlanTierRejection("""{"error":{"message":"Your current subscription does not have access"}}"""))
        assertTrue(isPlanTierRejection("This plan supports only kimi-k3 up to 256K context"))
        assertFalse(isPlanTierRejection("""{"error":{"type":"authentication_error","message":"invalid token"}}"""))
        assertFalse(isPlanTierRejection("expired_token"))
    }
}
