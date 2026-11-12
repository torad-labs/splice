// V4-38: the operator's grok login page kept reopening. Rule 1: a credential xAI names dead while it is demonstrably
// fine is never an expiry, so it renews nothing and opens no sign-in.
//
// Failure-handling slice 3: xAI's reader (GrokReplies) names the dead credential where the reply arrives, and its
// property test pins the billing and expiry bodies apart. The rule-3 phrase tests (the entitlement words choosing the
// sentence and its top-up link) and V4-73's billing-403-as-429 test pinned behaviour the redesign replaced: the person
// reads xAI's own words through the line's filter, and a 402 spending limit is the standard's NeedsPayment row.
package splice.provider.grok

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.RefreshAttempt
import java.nio.file.Files
import java.nio.file.Path

class GrokEntitlementTest {
    // ── rule 1: a dead credential on a fresh token is never an expiry ────────────────────

    @Test
    fun `a dead credential on a freshly refreshed token never allows a refresh`(@TempDir tempDir: Path) {
        val now = 1_000_000L
        // The operator's state: refreshed at 00:05, valid to 06:05 — hours outside the window.
        val auth = provider(tempDir, now + 6 * 3_600_000L, now)

        assertFalse(
            auth.allowRefreshAfterFailure(deadCredential = true),
            "a token xAI names dead while it is valid for hours is not an expiry",
        )
        // A refused login (401) is the SERVER contradicting the file, and a revoked token can be refused
        // while the file still reads hours out — so the freshness judgement must not reach it. Vetoing
        // here would be a regression, not a protection.
        assertTrue(auth.allowRefreshAfterFailure(deadCredential = false), "a refused login always keeps the refresh")
    }

    @Test
    fun `a genuine expiry still refreshes exactly as before, so 2026-07-18 does not regress`(
        @TempDir tempDir: Path,
        @TempDir tempDir2: Path,
    ) {
        val now = 1_000_000L
        // Inside the proactive window: the token is NOT demonstrably fine.
        val inside = provider(tempDir, now + 60_000L, now)
        assertTrue(inside.allowRefreshAfterFailure(deadCredential = true), "the dead-head incident's shape")

        val longPast = provider(tempDir2, now - 3_600_000L, now)
        assertTrue(longPast.allowRefreshAfterFailure(deadCredential = true), "an already-expired token")
    }

    @Test
    fun `a SYNTHESIZED expiry never vetoes a refresh — only a real expires may`(
        @TempDir tempDir: Path,
        @TempDir tempDir2: Path,
    ) {
        val now = 1_000_000L
        val withExpires = provider(tempDir, now + 6 * 3_600_000L, now)
        val withoutExpires = providerNoExpires(tempDir2, now)

        // The same dead-credential reading, two files that differ ONLY in whether they declare `expires`.
        // readSnapshot answers BOTH with an expiry hours past the proactive window: the second one
        // by SYNTHESIZING min(mtime, now) + 4h (G18/SH-01, GrokAuthJson lines 99-104). Reading that
        // ceiling as proof of freshness suppressed the refresh on a genuine expiry for the whole
        // synthesized window — the 2026-07-18 grok-dead-head shape, and the exact inversion
        // SynthesizedExpiry.kt:5 forbids ("can force an extra refresh, never suppress one").
        assertFalse(
            withExpires.allowRefreshAfterFailure(deadCredential = true),
            "a REAL expires hours out is the freshness evidence rule 1 is built on",
        )
        assertTrue(
            withoutExpires.allowRefreshAfterFailure(deadCredential = true),
            "an expires-less file proves NOTHING about the token, so the refresh must still run",
        )
        assertTrue(
            withoutExpires.allowRefreshAfterFailure(deadCredential = false),
            "a refused login still always keeps the refresh",
        )
    }

    @Test
    fun `an unreadable credential proves nothing, so the old behaviour stands`(@TempDir tempDir: Path) {
        val now = 1_000_000L
        val missing = tempDir.resolve("nope").resolve("auth.json")
        val auth = GrokAuthProvider(
            authPath = missing,
            authCacheMs = 30_000L,
            clock = { now },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
        )

        assertTrue(auth.allowRefreshAfterFailure(deadCredential = true), "no readable snapshot → no veto")
    }

    /** The drifted shape G18/SH-01 exist for: a real grok auth.json with NO top-level `expires` —
     *  a legacy file, or one the official grok CLI rewrote without it. Byte-for-byte [provider]'s
     *  file minus that one field, so the two tests above differ in nothing else. */
    private fun providerNoExpires(dir: Path, now: Long): GrokAuthProvider {
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"tokens":{"access_token":"grok-access","refresh_token":"grok-refresh"},""" +
                """"last_refresh":"2026-09-16T00:05:00Z"}""",
        )
        return GrokAuthProvider(
            authPath = file,
            authCacheMs = 30_000L,
            clock = { now },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
        )
    }

    private fun provider(dir: Path, expiresAtMs: Long, now: Long): GrokAuthProvider {
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"tokens":{"access_token":"grok-access","refresh_token":"grok-refresh"},""" +
                """"expires":$expiresAtMs,"last_refresh":"2026-09-16T00:05:00Z"}""",
        )
        return GrokAuthProvider(
            authPath = file,
            authCacheMs = 30_000L,
            clock = { now },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
        )
    }
}
