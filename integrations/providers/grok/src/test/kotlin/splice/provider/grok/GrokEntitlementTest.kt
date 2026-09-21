// V4-38: the operator's grok login page kept reopening. Two 403 bodies pin the two paths apart so
// they can never collapse into one again — a BILLING rejection (xAI personal-team-blocked /
// spending-limit) and a genuine EXPIRY (unauthenticated:bad-credentials, the 2026-07-18
// grok-dead-head shape). Rule 1: a 403 on a demonstrably-fine credential is never an expiry,
// whatever the body says. Rule 3: the entitlement phrases choose only the sentence.
package splice.provider.grok

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.RefreshAttempt
import java.nio.file.Files
import java.nio.file.Path

class GrokEntitlementTest {

    private val oauth = GrokOAuth()

    /** The body the live daemon logged, repeatedly, on the operator's own turn. */
    private val spendingLimit = """{"error":{"code":"personal-team-blocked",""" +
        """"message":"spending-limit: you have run out of credits or need a Grok subscription"}}"""

    /** The 2026-07-18 shape: xAI reporting an EXPIRED token as 403 rather than 401. */
    private val genuineExpiry = """{"error":{"code":"unauthenticated:bad-credentials",""" +
        """"message":"token is expired"}}"""

    // ── rule 3: the phrases choose only the sentence ──────────────────────────────────────

    @Test
    fun `the two 403 bodies are pinned against each other so they cannot collapse again`() {
        assertTrue(oauth.isEntitlementRejection(spendingLimit), "the billing 403 IS an entitlement rejection")
        assertFalse(oauth.isEntitlementRejection(genuineExpiry), "the expiry 403 is NOT an entitlement rejection")

        val sentence = oauth.entitlementSentence(spendingLimit)
        assertTrue(sentence?.contains("run out of credits") == true, sentence)
        assertNull(oauth.entitlementSentence(genuineExpiry), "an unrecognised body must fall back, never guess")
    }

    @Test
    fun `every xAI spelling is recognised case-insensitively, and status is never consulted`() {
        listOf(
            "personal-team-blocked",
            "spending-limit",
            "spending limit reached",
            "you have run out of credits",
            "You need a Grok subscription to continue",
        ).forEach { phrase ->
            assertTrue(oauth.isEntitlementRejection(phrase), phrase)
        }
        // The predicate takes a BODY, never a status: a 200-shaped body still matches, and an
        // auth-shaped body still does not. That is what keeps the string match out of the decision.
        assertFalse(oauth.isEntitlementRejection(""), "an empty body is never recognised")
    }

    @Test
    fun `a recognised body names the cause and carries the vendor's own link when it sends one`() {
        val withLink = """{"code":"personal-team-blocked","message":"spending-limit"},
            {"top_up":"https://x.ai/topup?ref=splice"}"""
        val sentence = oauth.entitlementSentence(withLink)
        assertTrue(sentence?.contains("https://x.ai/topup?ref=splice") == true, sentence)
        // splice invents no URL: without one in the body, the sentence still names the cause.
        assertFalse(oauth.entitlementSentence(spendingLimit)?.contains("https://") == true)

        // A link that is NOT the vendor's never rides. The body of a 403 is attacker-influenced in
        // the general case and was echoed verbatim until 2026-09-16: any https url matched, so a
        // redirect carrying a token in its query would have been printed to the operator.
        val foreign = """{"code":"spending-limit","top_up":"https://evil.test/x?token=abc"}"""
        val foreignSentence = oauth.entitlementSentence(foreign)
        assertNotNull(foreignSentence, "the body is still a recognised entitlement rejection")
        assertFalse(foreignSentence!!.contains("evil.test"), foreignSentence)
        assertFalse(foreignSentence.contains("token=abc"), foreignSentence)
    }

    // ── rule 1: a 403 on a fresh credential is never an expiry ────────────────────────────

    @Test
    fun `a 403 on a freshly refreshed token never allows a refresh, whatever the body says`() {
        val now = 1_000_000L
        // The operator's state: refreshed at 00:05, valid to 06:05 — hours outside the window.
        val auth = provider(Files.createTempDirectory("grok-fresh"), now + 6 * 3_600_000L, now)

        assertFalse(auth.allowRefreshAfterFailure(403, spendingLimit), "recognised billing 403")
        assertFalse(
            auth.allowRefreshAfterFailure(403, genuineExpiry),
            "an expiry-SPELLED body on a fresh token is still not an expiry — this is the unstringed half",
        )
        assertFalse(
            auth.allowRefreshAfterFailure(403, """{"error":"something nobody has seen before"}"""),
            "the whole point: an UNRECOGNISED 403 body is covered too",
        )
        // A 401 is the SERVER contradicting the file, and a revoked token can 401 while the file
        // still reads hours out — so the freshness judgement must not reach it. Vetoing here would
        // be a regression, not a protection.
        assertTrue(auth.allowRefreshAfterFailure(401, ""), "a 401 always keeps the refresh")
    }

    @Test
    fun `a genuine expiry still refreshes exactly as before, so 2026-07-18 does not regress`() {
        val now = 1_000_000L
        // Inside the proactive window: the token is NOT demonstrably fine.
        val inside = provider(Files.createTempDirectory("grok-window"), now + 60_000L, now)
        assertTrue(inside.allowRefreshAfterFailure(403, genuineExpiry), "the dead-head incident's shape")

        val longPast = provider(Files.createTempDirectory("grok-dead"), now - 3_600_000L, now)
        assertTrue(longPast.allowRefreshAfterFailure(403, genuineExpiry), "an already-expired token")
    }

    @Test
    fun `a SYNTHESIZED expiry never vetoes a refresh — only a real expires may`() {
        val now = 1_000_000L
        val withExpires = provider(Files.createTempDirectory("grok-real-expiry"), now + 6 * 3_600_000L, now)
        val withoutExpires = providerNoExpires(Files.createTempDirectory("grok-no-expiry"), now)

        // Same 403, same body, two files that differ ONLY in whether they declare `expires`.
        // readSnapshot answers BOTH with an expiry hours past the proactive window: the second one
        // by SYNTHESIZING min(mtime, now) + 4h (G18/SH-01, GrokAuthJson lines 99-104). Reading that
        // ceiling as proof of freshness suppressed the refresh on a genuine 403 expiry for the whole
        // synthesized window — the 2026-07-18 grok-dead-head shape, and the exact inversion
        // SynthesizedExpiry.kt:5 forbids ("can force an extra refresh, never suppress one").
        assertFalse(
            withExpires.allowRefreshAfterFailure(403, genuineExpiry),
            "a REAL expires hours out is the freshness evidence rule 1 is built on",
        )
        assertTrue(
            withoutExpires.allowRefreshAfterFailure(403, genuineExpiry),
            "an expires-less file proves NOTHING about the token, so the refresh must still run",
        )
        assertTrue(
            withoutExpires.allowRefreshAfterFailure(403, spendingLimit),
            "and a recognised billing body changes nothing: only a REAL expires may veto",
        )
        assertTrue(withoutExpires.allowRefreshAfterFailure(401, ""), "a 401 still always keeps the refresh")
    }

    @Test
    fun `an unreadable credential proves nothing, so the old behaviour stands`() {
        val now = 1_000_000L
        val missing = Files.createTempDirectory("grok-absent").resolve("nope").resolve("auth.json")
        val auth = GrokAuthProvider(
            authPath = missing,
            authCacheMs = 30_000L,
            clock = { now },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
        )

        assertTrue(auth.allowRefreshAfterFailure(403, spendingLimit), "no readable snapshot → no veto")
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

    // ── V4-73: the SPENT-ACCOUNT 403 is a rate limit, not an invalid request ────────────────
    // The billing wall used to reach the client as a terminal invalid_request_error, bypassing the
    // cooldown, the account pool and every client retry — when the operator's law is that credits
    // exhaustion is retried until the credits are back. The port is asked by the transport; this is
    // the only place that answers it with vendor spelling, and it answers with the SAME list V4-38
    // uses for the refresh veto, so the two can never disagree about what a quota wall looks like.
    @Test
    fun `a billing 403 is declared quota-exhausted, and nothing else is - V4-73`() {
        val auth = provider(Files.createTempDirectory("grok-quota"), expiresAtMs = 2_000_000L, now = 1_000_000L)

        assertTrue(auth.isQuotaExhausted(403, spendingLimit), "the billing 403 IS a quota wall")
        assertFalse(auth.isQuotaExhausted(403, genuineExpiry), "an EXPIRY 403 is not a quota wall")
        assertFalse(
            auth.isQuotaExhausted(403, "some unrecognised body"),
            "an unrecognised 403 keeps every bit of today's behaviour",
        )
        assertFalse(auth.isQuotaExhausted(401, spendingLimit), "a 401 is auth, never a quota wall")
        assertFalse(auth.isQuotaExhausted(429, spendingLimit), "an already-rate-limited status needs no rewrite")
    }
}
