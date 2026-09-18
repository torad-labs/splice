// NEW: V4-152 — split out of GrokAuthProviderTest.kt, which held SEVEN classes in one 830-line file
// and tripped detekt's LargeClass ceiling at 400. This class is NOT new: it already existed and
// already had its own JUnit report, so only its FILE was wrong. Its comment block and body arrive
// verbatim. Acceptance: :provider-grok:test reports the same total before and after.
package grok

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.provider.grok.GrokAuthProvider
import java.nio.file.Files

// DR-148 (provider sweep F5, 2026-08-31): GrokAuthJson keyed its cache on mtime ALONE while the
// codex twin also compared sizeBytes. This file is written concurrently by the official grok CLI by
// design, so a peer rotation landing inside the same filesystem timestamp tick was invisible and the
// STALE token kept being served for the whole authCacheMs window. Its own class because
// GrokAuthProviderTest is already at the LargeClass ceiling.
class GrokTornReadCacheTest {

    @Test
    fun `a same-mtime rewrite is not served from the cache - DR-148`() = runTest {
        val dir = Files.createTempDirectory("grok-torn-read")
        val now = 5_000_000_000L
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        // Explicitly Unit: Files.writeString returns Path, and an inferred lambda type makes
        // every call site a discarded-return warning.
        val write: (String, String) -> Unit = { access, pad ->
            Files.writeString(
                file,
                """{"tokens":{"access_token":"$access","refresh_token":"grok-refresh"},
                    "expires":${now + 3_600_000},"cli_field":"$pad"}""",
            )
        }
        write("token-A", "keep-me")
        val auth = GrokAuthProvider(
            authPath = file,
            // A generous TTL is the POINT: the arm must fail on staleness, never on expiry.
            authCacheMs = 600_000,
            clock = { now },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
        )
        assertEquals("token-A", (auth.credentials() as Credentials.Bearer).token)

        // The peer rewrite: a different token AND a different byte length, with the mtime forced
        // back to what the cache recorded. That is precisely the coarse-timestamp collision — same
        // tick, different bytes — and it is the only way to exercise the size half of the identity.
        val stamp = Files.getLastModifiedTime(file)
        write("token-B", "keep-me-and-then-some-more")
        Files.setLastModifiedTime(file, stamp)

        assertEquals(
            "token-B",
            (auth.credentials() as Credentials.Bearer).token,
            "a same-mtime rewrite of a DIFFERENT size must miss the cache, not serve the dead token",
        )
    }
}
