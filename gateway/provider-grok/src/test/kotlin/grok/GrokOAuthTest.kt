// NEW: grok OAuth building blocks — the authorize URL param set + %20 encoding, PKCE S256 shape,
// the code-exchange + refresh form bodies, and the token-response → auth.json mapping. These are
// the pieces `splice login grok` drives; pinned so a constant drift (client id, scope, endpoints)
// is a test failure, not a silent broken login.
package grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.grok.buildGrokAuthorizeUrl
import splice.provider.grok.grokAuthJsonFromTokenResponse
import splice.provider.grok.grokCodeExchangeForm
import splice.provider.grok.grokRefreshForm
import splice.provider.grok.makeGrokPkce

class GrokOAuthTest {

    private val noEnv: (String) -> String? = { null }

    @Test
    fun `endpoints match the grok CLI`() {
        assertEquals("b1a00492-073a-47ea-816f-4c329264a828", GrokOAuthEndpoints.clientId(noEnv))
        assertEquals("https://auth.x.ai/oauth2/authorize", GrokOAuthEndpoints.authorizeUrl(noEnv))
        assertEquals("https://auth.x.ai/oauth2/token", GrokOAuthEndpoints.tokenUrl(noEnv))
        assertEquals(56121, GrokOAuthEndpoints.REDIRECT_PORT)
        assertTrue(GrokOAuthEndpoints.SCOPE.contains("grok-cli:access"))
        assertTrue(GrokOAuthEndpoints.SCOPE.contains("api:access"))
    }

    @Test
    fun `authorize url has the pkce challenge, state, nonce and %20-encoded scope`() {
        val pkce = makeGrokPkce()
        val url = buildGrokAuthorizeUrl(
            pkce.challenge,
            "state-1",
            "nonce-1",
            GrokOAuthEndpoints.clientId(noEnv),
            noEnv,
        )
        assertTrue(url.startsWith("https://auth.x.ai/oauth2/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge=${pkce.challenge}"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state-1") && url.contains("nonce=nonce-1"))
        assertTrue(url.contains("scope=openid%20profile%20email")) // %20, never +
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A56121%2Fcallback"))
    }

    @Test
    fun `pkce verifier and challenge are distinct base64url`() {
        val pkce = makeGrokPkce()
        assertTrue(pkce.verifier.isNotEmpty() && pkce.challenge.isNotEmpty())
        assertTrue(pkce.verifier != pkce.challenge)
        assertTrue(pkce.verifier.none { it == '+' || it == '/' || it == '=' }) // base64url, no padding
    }

    @Test
    fun `exchange and refresh forms carry the right grant and client id`() {
        val exchange = grokCodeExchangeForm(
            "the-code",
            "the-verifier",
            "the-challenge",
            "cid",
            "http://127.0.0.1:56121/callback",
        )
        assertTrue(exchange.contains("grant_type=authorization_code"))
        assertTrue(exchange.contains("code=the-code"))
        assertTrue(exchange.contains("code_verifier=the-verifier"))
        assertTrue(exchange.contains("code_challenge_method=S256"))
        val refresh = grokRefreshForm("the-refresh", "cid")
        assertTrue(refresh.contains("grant_type=refresh_token"))
        assertTrue(refresh.contains("refresh_token=the-refresh"))
        assertTrue(refresh.contains("client_id=cid"))
    }

    @Test
    fun `token response maps to a grok auth json with tokens and expiry`() {
        val body = """{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}"""
        val auth = grokAuthJsonFromTokenResponse(
            body,
            fallbackRefresh = null,
            nowMs = 1000L,
            nowIso = "2026-07-16T00:00:00Z",
        )
        val obj = Json.parseToJsonElement(auth.toString()).jsonObject
        val tokens = obj["tokens"]!!.jsonObject
        assertEquals("at", tokens["access_token"]?.jsonPrimitive?.content)
        assertEquals("rt", tokens["refresh_token"]?.jsonPrimitive?.content)
        assertEquals("3601000", obj["expires"]?.jsonPrimitive?.content) // 1000 + 3600*1000
    }

    @Test
    fun `token response without a new refresh token keeps the fallback`() {
        val body = """{"access_token":"at2","expires_in":3600}"""
        val auth = grokAuthJsonFromTokenResponse(body, fallbackRefresh = "old-refresh", nowMs = 0L, nowIso = "z")
        val tokens = Json.parseToJsonElement(auth.toString()).jsonObject["tokens"]!!.jsonObject
        assertEquals("old-refresh", tokens["refresh_token"]?.jsonPrimitive?.content)
    }
}
