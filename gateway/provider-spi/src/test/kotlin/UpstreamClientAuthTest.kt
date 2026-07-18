// NEW: pins for the refresh trigger predicate (grok-dead-head incident, 2026-07-18): xAI reports
// an expired OAuth token as 403 `unauthenticated:bad-credentials`, not 401 — the old
// `status == 401` gate meant refresh never fired and the head served a dead token until manual
// re-login. 401 always refreshes; 403 only with an auth-signature body, so a plan/permission 403
// cannot spend the single refresh.
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.UpstreamClient

class UpstreamClientAuthTest {

    @Test
    fun `401 is refreshable regardless of body`() {
        assertTrue(UpstreamClient.isAuthRefreshableFailure(401, ""))
        assertTrue(UpstreamClient.isAuthRefreshableFailure(401, "anything"))
    }

    @Test
    fun `403 with xai bad-credentials body is refreshable`() {
        assertTrue(
            UpstreamClient.isAuthRefreshableFailure(
                403,
                """{"code":"unauthenticated:bad-credentials",""" +
                    """"error":"The OAuth2 access token could not be validated."}""",
            ),
        )
    }

    @Test
    fun `403 with token expired body is refreshable`() {
        assertTrue(UpstreamClient.isAuthRefreshableFailure(403, """{"error":"token expired"}"""))
    }

    @Test
    fun `permission or plan 403 is not refreshable`() {
        assertFalse(
            UpstreamClient.isAuthRefreshableFailure(403, """{"error":"model not available on your plan"}"""),
        )
        assertFalse(UpstreamClient.isAuthRefreshableFailure(403, ""))
    }

    @Test
    fun `other statuses never trigger refresh`() {
        assertFalse(UpstreamClient.isAuthRefreshableFailure(429, "unauthenticated"))
        assertFalse(UpstreamClient.isAuthRefreshableFailure(500, "token expired"))
    }
}
