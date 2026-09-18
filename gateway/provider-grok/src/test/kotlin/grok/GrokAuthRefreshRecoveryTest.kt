// NEW: V4-152 — split out of GrokAuthProviderTest.kt on a BEHAVIOURAL seam, not a line count: the
// original was 552 lines and held the lifecycle pins AND the failure-recovery pins, which are two
// different machines. This class owns the second: what happens when the refresh path does not simply
// work — a peer rotating underneath us, a bounded retry, a bounded dead refresh, the invalid_grant
// latch, and the SH-02 backoff branches. The cases and their comments arrive VERBATIM; only the
// class name and the helpers are new, and the helpers are copies because that is the idiom an
// earlier split in the same file established (a shared helper file would couple two classes whose
// only relationship is that they once shared a file).
//
// The split's acceptance is the total: :provider-grok:test must report the same count before and
// after, because a dropped case leaves detekt green and the suite green and shows up in nothing else.
package grok
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger

class GrokAuthRefreshRecoveryTest {
    private fun authFile(
        dir: Path,
        access: String = "grok-access",
        refresh: String = "grok-refresh",
        expiresAtMs: Long? = null,
    ): Path {
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        val expires = expiresAtMs?.let { """"expires":$it,""" }.orEmpty()
        Files.writeString(
            file,
            """{"tokens":{"access_token":"$access","refresh_token":"$refresh"},
                $expires"cli_field":"keep-me"}""",
        )
        return file
    }

    private fun bearerToken(creds: Credentials?): String {
        assertTrue(creds is Credentials.Bearer)
        return (creds as Credentials.Bearer).token
    }

    // G1: a peer process (or the official grok CLI) rotated the token on disk while we were about to
    // refresh. The freshly-read access token differs from what we last served, so the POST is skipped
    // and the peer's token is served — no wasted refresh, no double token burn.
    @Test
    fun `peer already rotated while we were about to refresh - POST skipped, peer token served`() = runTest {
        val dir = Files.createTempDirectory("grok-peer")
        val now = 1_000_000L
        // prime the in-memory cache with token A (expiry outside the window so no refresh on read).
        val file = authFile(dir, access = "token-A", expiresAtMs = now + 3_600_000)
        val calls = AtomicInteger()
        val auth = GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { now }, refreshCall = {
            calls.incrementAndGet()
            RefreshAttempt.Denied("test-denied")
        })
        assertEquals("token-A", bearerToken(auth.credentials())) // cache now holds A
        // a concurrent process rotates the file to token B underneath us.
        Files.writeString(
            file,
            """{"tokens":{"access_token":"token-B","refresh_token":"grok-refresh"},
                "expires":${now + 3_600_000},"cli_field":"keep-me"}""",
        )
        val beforeContent = Files.readString(file)
        assertEquals("token-B", bearerToken(auth.refresh())) // adopts B, no POST
        assertEquals(0, calls.get())
        assertEquals(beforeContent, Files.readString(file)) // no extra write
    }

    // G1: the endpoint rejects R1, but disk shows a rotation to R2 landed underneath us between our
    // read and the POST — retry ONCE against R2, which succeeds. Exactly two POSTs, no more.
    @Test
    fun `refresh rejected once but the disk-fresh refresh token succeeds - one bounded retry`() = runTest {
        val dir = Files.createTempDirectory("grok-retry")
        val file = authFile(dir, access = "acc", refresh = "R1")
        val seen = mutableListOf<String>()
        val auth =
            GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { 1_000_000L }, refreshCall = { token ->
                seen.add(token)
                if (token == "R1") {
                    // another process's rotation lands on disk between our read and the POST reaching xAI.
                    Files.writeString(file, """{"tokens":{"access_token":"acc","refresh_token":"R2"}}""")
                    RefreshAttempt.Denied("xAI rejected R1")
                } else {
                    RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "new-refresh", expiresIn = 21_600))
                }
            })
        assertEquals("new-access", bearerToken(auth.refresh()))
        assertEquals(listOf("R1", "R2"), seen) // exactly two POSTs, R1 then the disk-fresh R2
        val onDisk = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("new-refresh", onDisk["tokens"]!!.jsonObject["refresh_token"]!!.jsonPrimitive.content)
    }

    // G1: the retry is bounded even when the disk token keeps rotating and every POST is rejected —
    // exactly two POSTs, then it gives up (the retry POSTs with allowRereadRetry=false, never loops).
    @Test
    fun `refresh genuinely dead - bounded to two POSTs, no infinite retry`() = runTest {
        val dir = Files.createTempDirectory("grok-bounded")
        val file = authFile(dir, access = "acc", refresh = "R1")
        val calls = AtomicInteger()
        val auth = GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { 1_000_000L }, refreshCall = {
            val n = calls.incrementAndGet()
            // rotate to a NEW distinct token on every call, and always reject — proves the retry is
            // capped, not driven-forever by continuous disk changes.
            Files.writeString(file, """{"tokens":{"access_token":"acc","refresh_token":"R${n + 1}"}}""")
            RefreshAttempt.Denied("test-denied")
        })
        assertNull(auth.refresh())
        assertEquals(2, calls.get())
    }

    @Test
    fun `refresh response without expires_in synthesizes a new expires field - SH-02 rewrite`() = runTest {
        // SH-02 REWRITE of the old keeps-the-old-expires pin: carrying the stale value was the
        // refresh-ineffective loop (every next call re-entered the blocking tier and burned a
        // rotating refresh token). A just-minted token synthesizes now+TTL instead.
        val dir = Files.createTempDirectory("grok-noexp")
        val now = 1_000_000L
        val oldExpires = now - 1
        val file = authFile(dir, expiresAtMs = oldExpires)
        val auth = GrokAuthProvider(
            authPath = file,
            authCacheMs = 30_000L,
            clock = { now },
            refreshCall = {
                RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "new-refresh", expiresIn = null))
            },
        )
        assertEquals("new-access", bearerToken(auth.credentials()))
        val onDisk = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val persisted = onDisk["expires"]!!.jsonPrimitive.content.toLong()
        assertTrue(persisted > now, "expires must ADVANCE past now (was $oldExpires, got $persisted)")
    }

    // G15: a confirmed invalid_grant (post-G1 re-read: disk untouched, so the retry-once check
    // finds no rotation and gives up) latches — the SECOND call must not re-POST the dead token.
    @Test
    fun `latched invalid_grant skips the network POST on the next call`() = runTest {
        val dir = Files.createTempDirectory("grok-latch")
        val file = authFile(dir, refresh = "dead-refresh")
        val calls = AtomicInteger()
        val auth = GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { 1_000_000L }, refreshCall = {
            calls.incrementAndGet()
            RefreshAttempt.InvalidGrant("dead")
        })
        assertNull(auth.refresh())
        assertEquals(1, calls.get())
        assertNull(auth.refresh()) // file untouched: gate short-circuits before the lock/network
        assertEquals(1, calls.get())
    }

    // G15: the latch is keyed on the auth file's mtime — a re-login rewrite (fresh refresh token,
    // new mtime) clears it automatically, so the very next call attempts a real refresh again.
    @Test
    fun `latch clears when the auth file's mtime changes`() = runTest {
        val dir = Files.createTempDirectory("grok-unlatch")
        val file = authFile(dir, refresh = "dead-refresh")
        val calls = AtomicInteger()
        var granted = false
        val auth = GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { 1_000_000L }, refreshCall = {
            calls.incrementAndGet()
            if (granted) {
                RefreshAttempt.Granted(GrokRefreshedTokens("rotated-access", "rotated-refresh", expiresIn = 21_600))
            } else {
                RefreshAttempt.InvalidGrant("dead")
            }
        })
        assertNull(auth.refresh())
        assertEquals(1, calls.get())
        Thread.sleep(5) // guarantee the mtime actually advances on coarse-grained filesystems
        authFile(dir, access = "grok-access", refresh = "fresh-refresh") // re-login rewrites the file
        granted = true
        assertEquals("rotated-access", bearerToken(auth.refresh()))
        assertEquals(2, calls.get()) // the real POST fired — the latch did not suppress it
    }

    // G15: /mgmt/auth and /api/auth surface the suppressed state via describe().
    @Test
    fun `describe surfaces refresh_latched after a confirmed invalid_grant`() = runTest {
        val dir = Files.createTempDirectory("grok-latch-desc")
        val file = authFile(dir, refresh = "dead-refresh")
        val auth = GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { 1_000_000L }, refreshCall = {
            RefreshAttempt.InvalidGrant("dead")
        })
        assertNull(auth.describe().fields["refresh_latched"])
        assertNull(auth.refresh())
        assertEquals("invalid_grant", auth.describe().fields["refresh_latched"])
    }

    // Sweep 2026-08-31 (absence-class): the G1 confirming reread can itself FAIL. Unfixed, that
    // failure was swallowed and the bare invalid_grant reason armed the latch UNCONFIRMED —
    // breaking G15's own "one that survived that race check" contract. The composite reason
    // names the read failure and never latches (codex twin parity).
    @Test
    fun `a failed confirming reread names the failure and never arms the latch`() = runTest {
        val dir = Files.createTempDirectory("grok-reread")
        val file = authFile(dir, refresh = "dead-refresh")
        val log = mutableListOf<String>()
        val auth = GrokAuthProvider(
            authPath = file,
            authCacheMs = 30_000L,
            clock = { 1_000_000L },
            refreshCall = {
                Files.setPosixFilePermissions(
                    dir,
                    java.nio.file.attribute.PosixFilePermissions.fromString("---------"),
                )
                RefreshAttempt.InvalidGrant("dead")
            },
            log = splice.core.util.LogSink { log += it },
        )
        try {
            assertNull(auth.refresh())
        } finally {
            Files.setPosixFilePermissions(
                dir,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
            )
        }
        assertTrue(log.any { it.contains("credential reread failed") }, "unconfirmed rejection must be named: $log")
        assertNull(auth.describe().fields["refresh_latched"], "an unconfirmed invalid_grant must not latch")
    }

    // DR-65 (codex security probe): a malformed auth.json still containing a live token must not
    // leak it through parse-exception text ("JSON input:" excerpts) into logs or describe fields.
    @Test
    fun `diagnostics never quote credential bytes from a malformed auth file - DR-65`() = runTest {
        val sentinel = "xai-SENTINEL-LEAK-CANARY"
        val dir = Files.createTempDirectory("grok-leak")
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"tokens":{"access_token":"$sentinel"""")
        val log = mutableListOf<String>()
        val auth = GrokAuthProvider(
            authPath = file,
            authCacheMs = 30_000L,
            clock = { 1_000_000L },
            refreshCall = { RefreshAttempt.Denied("must-not-be-reached") },
            log = splice.core.util.LogSink { log += it },
        )
        assertNull(auth.credentials())
        assertNull(auth.refresh())
        val surfaced = (log + auth.describe().fields.map { "${it.key}=${it.value}" }).joinToString("\n")
        assertTrue(!surfaced.contains(sentinel), "credential bytes must never surface: $surfaced")
        assertTrue(log.any { it.contains("NOT a logged-out state") }, "diagnostics still classify: $log")
    }

    @Test
    fun `granted refresh with no expires_in advances the expiry - one refresh across N calls - SH-02a`() = runTest {
        // Pre-fix: null expiresIn persisted a null expiry, the merge kept the stale on-disk value,
        // and every credentials() call below the stale floor blocked on ANOTHER refresh — each one
        // consuming a rotating refresh token. The synthesized now+TTL expiry kills the loop.
        val dir = Files.createTempDirectory("grok-noexpin")
        var now = 1_000_000L
        val file = authFile(dir, expiresAtMs = now + 1_000) // inside the stale floor: blocking tier
        val calls = AtomicInteger(0)
        val auth = GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { now }, refreshCall = {
            calls.incrementAndGet()
            RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "new-refresh", expiresIn = null))
        })
        repeat(5) { assertEquals("new-access", bearerToken(auth.credentials())) }
        assertEquals(1, calls.get(), "an expires_in-less grant must refresh once, not per call")
        assertEquals(0, auth.ineffectiveRefreshCount, "the synthesized expiry advanced — not ineffective")
    }

    @Test
    fun `sub-floor grant trips the ineffective backoff - one refresh, logged once - SH-02b`() = runTest {
        // A grant whose expires_in cannot satisfy the stale floor is a SUCCESSFUL refresh the tier
        // logic will re-request forever. The guard logs, counts, and serves the current token
        // through the 30s backoff window instead.
        val dir = Files.createTempDirectory("grok-subfloor")
        var now = 1_000_000L
        val file = authFile(dir, expiresAtMs = now + 1_000)
        val calls = AtomicInteger(0)
        val logs = mutableListOf<String>()
        val auth =
            GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { now }, log = logs::add, refreshCall = {
                calls.incrementAndGet()
                RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "new-refresh", expiresIn = 10))
            })
        repeat(5) { assertEquals("new-access", bearerToken(auth.credentials())) }
        assertEquals(1, calls.get(), "the backoff must absorb the re-entering tier, not refresh per call")
        assertEquals(1, auth.ineffectiveRefreshCount)
        assertEquals(1, logs.count { it.contains("did not advance") }, "logged once, got $logs")
        // the backoff lapses: the next stale-floor entry may refresh again
        now += 31_000
        auth.credentials()
        assertEquals(2, calls.get(), "after the backoff window a refresh is allowed again")
    }
}
