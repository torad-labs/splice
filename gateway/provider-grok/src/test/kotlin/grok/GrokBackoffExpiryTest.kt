// NEW: V4-152 — split out of GrokAuthProviderTest.kt, which held SEVEN classes in one 830-line file
// and tripped detekt's LargeClass ceiling at 400. This class is NOT new: it already existed and
// already had its own JUnit report, so only its FILE was wrong. Its body arrives verbatim, including
// the two private helpers it carries rather than shares — the idiom an earlier split in this file
// established, and the reason relocation is behaviour-free. Acceptance: :provider-grok:test reports
// the same total before and after.
package grok

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.provider.grok.GrokAuthProvider
import splice.provider.grok.GrokRefreshedTokens
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** DR-146's arm, in its own class because GrokAuthProviderTest is at detekt's LargeClass ceiling. */
class GrokBackoffExpiryTest {

    private fun authFile(dir: Path, expiresAtMs: Long): Path {
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"tokens":{"access_token":"grok-access","refresh_token":"grok-refresh"},
                "expires":$expiresAtMs,"cli_field":"keep-me"}""",
        )
        return file
    }

    private fun bearerToken(creds: Credentials?): String {
        assertTrue(creds is Credentials.Bearer)
        return (creds as Credentials.Bearer).token
    }

    // DR-146 (provider sweep, 2026-08-31): the SH-02(b) backoff branch served `current`
    // UNCONDITIONALLY, where the stale-floor branch three lines below has always refused a token
    // past its own expiry. A sub-floor grant arms the backoff; once that token passes its expiry
    // INSIDE the 30s window, a KNOWN-DEAD token went to UpstreamClient, which spent a real upstream
    // call to collect a 403 and then burned the single-flight refresh anyway — so the branch's own
    // stated goal, not burning a rotating refresh token per request, was not achieved on the
    // reactive path, and each request also cost a wasted round trip. The existing SH-02b arm holds
    // "backoff armed" and the +31s arm holds "token lapsed"; neither holds BOTH at once, which is
    // the only state where this is visible.
    @Test
    fun `an armed backoff never serves a token past its own expiry - DR-146`() = runTest {
        val dir = Files.createTempDirectory("grok-backoff-expired")
        var now = 1_000_000L
        val file = authFile(dir, expiresAtMs = now + 1_000)
        val calls = AtomicInteger(0)
        val auth = GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { now }, refreshCall = {
            calls.incrementAndGet()
            RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "new-refresh", expiresIn = 10))
        })
        // The sub-floor grant is served and arms SH-02(b): exactly one refresh.
        assertEquals("new-access", bearerToken(auth.credentials()))
        assertEquals(1, calls.get())

        // Inside the 30s backoff window, but PAST the granted token's own 10s expiry.
        now += 10_001
        assertNull(auth.credentials(), "a token past its own expiry must never be served, backoff or not")
        assertEquals(1, calls.get(), "and the backoff still suppresses the refresh it was armed to suppress")
    }
}
