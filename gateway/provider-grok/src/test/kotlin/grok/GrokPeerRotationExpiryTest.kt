// NEW: V4-152 — split out of GrokAuthProviderTest.kt, which held SEVEN classes in one 830-line file
// and tripped detekt's LargeClass ceiling at 400. This class is NOT new: it already existed and
// already had its own JUnit report, so only its FILE was wrong. It arrives verbatim, including the
// two private helpers it carries rather than shares — the idiom an earlier split in this file
// established. Acceptance: :provider-grok:test reports the same total before and after.
package grok

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.core.auth.SYNTHETIC_EXPIRY_TTL_MS
import splice.provider.grok.GrokAuthProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger

/** DR-145's peer-rotation expiry arm, in its own class because GrokAuthProviderTest is at detekt's
 *  LargeClass ceiling. Carries its own copies of the two helpers it needs — they are private to the
 *  sibling class above. */
class GrokPeerRotationExpiryTest {

    private fun authFile(dir: Path, access: String, expiresAtMs: Long?): Path {
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        val expires = expiresAtMs?.let { """"expires":$it,""" }.orEmpty()
        Files.writeString(
            file,
            """{"tokens":{"access_token":"$access","refresh_token":"grok-refresh"},
                $expires"cli_field":"keep-me"}""",
        )
        return file
    }

    private fun bearerToken(creds: Credentials?): String {
        assertTrue(creds is Credentials.Bearer)
        return (creds as Credentials.Bearer).token
    }

    // DR-145 (provider sweep, 2026-08-31): peerRotation is a SECOND writer of the credential cache
    // and it skipped the SH-01/G18 synthesized-expiry policy that readSnapshot applies. An adopted
    // token from a drifted file — the shape those items exist for, with no top-level `expires` —
    // was cached with a NULL expiry and then served as never-expiring: no proactive refresh, no
    // stale floor, no shape-drift warning, until a mid-turn 401. GrokAuthProvider's own comment
    // claims "expiresAt is always populated now" and that the null branch is defensive-only dead
    // code; its sibling method made that false. Kimi does not have the bug because KimiOAuth
    // synthesizes inside parseSnapshot, which is what marks this as drift rather than design.
    //
    // The existing peer arms cannot catch it: they assert only the served token and call count, and
    // the G1 arm above writes an `expires`, so it never exercises the drifted shape at all.
    @Test
    fun `a peer-adopted token with no expires gets the synthesized ceiling - DR-145`() = runTest {
        val dir = Files.createTempDirectory("grok-peer-expiry")
        val now = 5_000_000_000L
        val file = authFile(dir, access = "token-A", expiresAtMs = now + 3_600_000)
        val calls = AtomicInteger()
        val auth = GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { now }, refreshCall = {
            calls.incrementAndGet()
            RefreshAttempt.Denied("test-denied")
        })
        assertEquals("token-A", bearerToken(auth.credentials())) // cache holds A

        // The peer rotates to token-B and writes NO `expires` — the G18/SH-01 drifted shape.
        Files.writeString(
            file,
            """{"tokens":{"access_token":"token-B","refresh_token":"grok-refresh"},"cli_field":"keep"}""",
        )
        // Pin the mtime so the synthesized ceiling (mtime + 4h) leaves 10s — inside the 30s floor.
        Files.setLastModifiedTime(file, FileTime.fromMillis(now + 10_000 - SYNTHETIC_EXPIRY_TTL_MS))

        assertEquals("token-B", bearerToken(auth.refresh())) // adopts B
        assertEquals(0, calls.get(), "adoption is still POST-free")

        // The claim: a HOT read at the SAME clock. The adopted snapshot must carry the synthesized
        // ceiling, so this sits below the stale floor and must BLOCK for a refresh. Deliberately no
        // clock jump — advancing +4h would miss the cache and pass for the wrong reason.
        auth.credentials()
        assertEquals(1, calls.get(), "an adopted expiry-less token must not be served as never-expiring")
    }
}
