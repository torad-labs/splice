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
import splice.core.auth.SYNTHETIC_EXPIRY_TTL_MS
import splice.provider.grok.GrokOAuth
import splice.provider.grok.GrokOAuthEndpoints

class GrokOAuthTest {

    private val oauth = GrokOAuth()
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
    fun `authorize url has the pkce challenge, state, nonce and percent-20-encoded scope`() {
        val pkce = oauth.makeGrokPkce()
        val url = oauth.buildGrokAuthorizeUrl(
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
        val pkce = oauth.makeGrokPkce()
        assertTrue(pkce.verifier.isNotEmpty() && pkce.challenge.isNotEmpty())
        assertTrue(pkce.verifier != pkce.challenge)
        assertTrue(pkce.verifier.none { it == '+' || it == '/' || it == '=' }) // base64url, no padding
    }

    @Test
    fun `exchange and refresh forms carry the right grant and client id`() {
        val exchange = oauth.grokCodeExchangeForm(
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
        val refresh = oauth.grokRefreshForm("the-refresh", "cid")
        assertTrue(refresh.contains("grant_type=refresh_token"))
        assertTrue(refresh.contains("refresh_token=the-refresh"))
        assertTrue(refresh.contains("client_id=cid"))
    }

    @Test
    fun `token response maps to a grok auth json with tokens and expiry`() {
        val body = """{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}"""
        val auth = oauth.grokAuthJsonFromTokenResponse(
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

    // DR-177: expires_in came off the wire and went straight into `nowMs + it * 1000`. An absurd
    // value WRAPPED, so the persisted `expires` was a large NEGATIVE instant — the credential read
    // as expired on every single turn, every turn refreshed, and the refresh returned the same
    // field. A permanent storm out of one number, and the file said so in plain sight.
    @Test
    fun `an absurd expires_in cannot persist an expiry in the past - DR-177`() {
        val absurd = Long.MAX_VALUE / 1000 + 1
        val body = """{"access_token":"at","refresh_token":"rt","expires_in":$absurd}"""
        val auth = oauth.grokAuthJsonFromTokenResponse(body, fallbackRefresh = null, nowMs = 1000L, nowIso = "z")
        val expires = Json.parseToJsonElement(auth.toString()).jsonObject["expires"]!!.jsonPrimitive.content.toLong()
        assertTrue(expires > 1000L, "a wrapped expiry reads as already-expired forever: got $expires")
        assertEquals(1000L + SYNTHETIC_EXPIRY_TTL_MS, expires, "an unusable lifetime takes the synthesized ceiling")
    }

    @Test
    fun `token response without a new refresh token keeps the fallback`() {
        val body = """{"access_token":"at2","expires_in":3600}"""
        val auth = oauth.grokAuthJsonFromTokenResponse(body, fallbackRefresh = "old-refresh", nowMs = 0L, nowIso = "z")
        val tokens = Json.parseToJsonElement(auth.toString()).jsonObject["tokens"]!!.jsonObject
        assertEquals("old-refresh", tokens["refresh_token"]?.jsonPrimitive?.content)
    }
}
