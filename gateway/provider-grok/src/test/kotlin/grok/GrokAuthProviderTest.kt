// NEW: GrokAuthProvider proactive-refresh pins (grok-dead-head incident, 2026-07-18: xAI 403s an
// expired token, the reactive 401 path never fired, the head died until manual re-login). Fake
// clock + injected refreshCall, no network (mirrors KimiAuthProviderTest): refresh fires inside
// the proactive window and persists rotated tokens + the NEW `expires`; a failed refresh on a
// not-yet-expired token still serves the current one; a fully expired token with a dead refresh
// yields null; foreign fields the official grok CLI stores beside ours survive the merge.
package grok

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.core.auth.SYNTHETIC_EXPIRY_TTL_MS
import splice.core.util.LogSink
import splice.provider.grok.GrokAuthProvider
import splice.provider.grok.GrokRefreshedTokens
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText

// DR-186: the backstop InflightGateTest already puts on its racing arm ("a genuine leak hangs, and
// must FAIL the suite, never wedge it"), applied to the other unbounded spin-wait. JUnit's
// TimeoutInvocation schedules a real interrupt and runBlocking's joinBlocking tests
// Thread.interrupted() on every turn of its event loop, so this bounds a spin that yield() alone
// never will. Generous on purpose: it is a hang backstop, not a latency assertion.
private const val HANG_BACKSTOP_S = 60L

class GrokAuthProviderTest {

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

    @Test
    fun `token outside the proactive window serves without refreshing`() = runTest {
        val dir = Files.createTempDirectory("grok-fresh")
        val now = 1_000_000L
        val file = authFile(dir, expiresAtMs = now + 3_600_000)
        val calls = AtomicInteger()
        val auth = GrokAuthProvider(authPath = file, clock = { now }, refreshCall = {
            calls.incrementAndGet()
            RefreshAttempt.Denied("test-denied")
        })
        assertEquals("grok-access", bearerToken(auth.credentials()))
        assertEquals(0, calls.get())
    }

    @Test
    fun `file without expires serves as-is (legacy shape)`() = runTest {
        val dir = Files.createTempDirectory("grok-legacy")
        val file = authFile(dir, expiresAtMs = null)
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { 1_000_000L },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
        )
        assertEquals("grok-access", bearerToken(auth.credentials()))
    }

    // G18: a file with no top-level `expires` (legacy shape, or a foreign CLI write that stripped
    // it) is no longer never-expiring — readSnapshot() synthesizes expiresAtMs = mtime + 4h. These
    // three tests pin mtime directly (Files.setLastModifiedTime) to land the synthesized value in
    // each of the three credentials() tiers.
    @Test
    fun `synthesized expiry from mtime outside proactive window serves as-is`() = runTest {
        val dir = Files.createTempDirectory("grok-synth-fresh")
        val now = 1_000_000L
        val file = authFile(dir, expiresAtMs = null)
        // mtime + 4h lands far outside the 5-minute proactive window.
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - 1_000_000))
        val calls = AtomicInteger()
        val auth = GrokAuthProvider(authPath = file, clock = { now }, refreshCall = {
            calls.incrementAndGet()
            RefreshAttempt.Denied("test-denied")
        })
        assertEquals("grok-access", bearerToken(auth.credentials()))
        assertEquals(0, calls.get())
    }

    // G18: mtime placed so the synthesized expiry (mtime + 4h) has 10s left — below the 30s stale
    // floor (G17), so credentials() blocks for a confirmed-fresh token instead of only prefetching.
    // `now` is scaled up from the 1_000_000L convention used elsewhere so subtracting most of the
    // 4h TTL doesn't push mtime before the epoch.
    @Test
    fun `synthesized expiry from mtime inside proactive window triggers proactive refresh`() = runTest {
        val dir = Files.createTempDirectory("grok-synth-inside")
        val now = 100_000_000L
        val file = authFile(dir, expiresAtMs = null)
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - (4 * 3_600_000L - 10_000)))
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { now },
            nowIso = { "iso-now" },
            refreshCall = {
                RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "new-refresh", expiresIn = 21_600))
            },
        )
        assertEquals("new-access", bearerToken(auth.credentials()))
        val onDisk = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val tokens = onDisk["tokens"]!!.jsonObject
        assertEquals("new-access", tokens["access_token"]!!.jsonPrimitive.content)
        assertEquals("new-refresh", tokens["refresh_token"]!!.jsonPrimitive.content)
        assertEquals(now + 21_600 * 1000, onDisk["expires"]!!.jsonPrimitive.content.toLong())
    }

    // G18: mtime placed so the 4h TTL has already fully elapsed (synthesized expiry is 1ms in the
    // past) and the refresh comes back dead — mirrors `fully expired token with dead refresh yields
    // null` but for the synthesized-TTL path instead of an explicit `expires` field.
    @Test
    fun `synthesized expiry fully elapsed with dead refresh yields null`() = runTest {
        val dir = Files.createTempDirectory("grok-synth-dead")
        val now = 100_000_000L
        val file = authFile(dir, expiresAtMs = null)
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - (4 * 3_600_000L + 1)))
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { now },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
        )
        assertNull(auth.credentials())
    }

    @Test
    fun `expired token refreshes proactively and persists rotation plus new expires`() = runTest {
        val dir = Files.createTempDirectory("grok-expired")
        val now = 1_000_000L
        val file = authFile(dir, expiresAtMs = now - 1) // already past expiry
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { now },
            nowIso = { "iso-now" },
            refreshCall = {
                RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "new-refresh", expiresIn = 21_600))
            },
        )
        assertEquals("new-access", bearerToken(auth.credentials()))
        val onDisk = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val tokens = onDisk["tokens"]!!.jsonObject
        assertEquals("new-access", tokens["access_token"]!!.jsonPrimitive.content)
        assertEquals("new-refresh", tokens["refresh_token"]!!.jsonPrimitive.content)
        assertEquals(now + 21_600 * 1000, onDisk["expires"]!!.jsonPrimitive.content.toLong())
        assertEquals("keep-me", onDisk["cli_field"]!!.jsonPrimitive.content) // CLI fields survive
    }

    // G17: 60s remaining is inside the 5-minute proactive window but above the 30s stale floor, so
    // this lands in the prefetch tier — the background refresh is fire-and-forget, so a failed
    // refreshCall never affects the return value; the current token comes back immediately either way.
    @Test
    fun `above the stale floor (prefetch tier), a failed background refresh still serves the current token`() = runTest {
        val dir = Files.createTempDirectory("grok-graceful")
        val now = 1_000_000L
        val file = authFile(dir, expiresAtMs = now + 60_000) // < 5 min window, >= 30s floor
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { now },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
        )
        assertEquals("grok-access", bearerToken(auth.credentials()))
    }

    // G17: 10s remaining is below the 30s stale floor — too close to hard expiry to risk serving a
    // token that might not survive the request, so credentials() still blocks synchronously and
    // returns the FRESH token. The old single-tier suite only exercised blocking via already-past-
    // expiry fixtures; this isolates the "still valid but below the floor" case.
    @Test
    fun `below the stale floor, credentials() blocks and returns the refreshed token`() = runTest {
        val dir = Files.createTempDirectory("grok-floor")
        val now = 1_000_000L
        val file = authFile(dir, expiresAtMs = now + 10_000) // < 30s floor
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { now },
            refreshCall = {
                RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "new-refresh", expiresIn = 21_600))
            },
        )
        assertEquals("new-access", bearerToken(auth.credentials()))
    }

    // BS-2: a filesystem hiccup during persistRotation's write (disk full, perms, NFS blip) must not
    // throw through SingleFlight/credentials() — the endpoint already burned the old refresh_token
    // (Granted), so a lost write must still serve the not-yet-expired CURRENT token, never an exception.
    @Test
    fun `write failure during persist serves the current not-yet-expired token, never throws`() = runTest {
        val dir = Files.createTempDirectory("grok-persist-fail")
        val now = 1_000_000L
        val file = authFile(dir, expiresAtMs = now + 10_000) // < 30s floor, still valid
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { now },
            refreshCall = {
                RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "new-refresh", expiresIn = 21_600))
            },
        )
        // pre-create the CredentialLock sibling so its own file open doesn't need dir-write access.
        Files.createFile(file.resolveSibling("${file.fileName}.lock"))
        val writablePerms = Files.getPosixFilePermissions(file.parent)
        Files.setPosixFilePermissions(
            file.parent,
            java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"),
        )
        try {
            assertEquals("grok-access", bearerToken(auth.credentials()))
        } finally {
            Files.setPosixFilePermissions(file.parent, writablePerms)
        }
    }

    // G17: proves the prefetch tier is truly fire-and-forget on a real dispatcher — if credentials()
    // still awaited the refresh synchronously, this would deadlock/timeout on the un-completed gate.
    // Mirrors KimiAuthProviderTest's "two concurrent refreshes coalesce" idiom (runBlocking, not
    // runTest, for deterministic real-dispatcher async proof).
    @Test
    @Timeout(HANG_BACKSTOP_S) // DR-186: two SPINS below, and a spin that never ends wedges the suite
    fun `prefetch tier does not block on a slow background refresh`() = runBlocking {
        val dir = Files.createTempDirectory("grok-prefetch-async")
        val now = 1_000_000L
        val file = authFile(dir, expiresAtMs = now + 120_000) // inside window, above the floor
        val calls = AtomicInteger()
        val gate = CompletableDeferred<GrokRefreshedTokens?>()
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { now },
            refreshCall = {
                calls.incrementAndGet()
                val tokens = gate.await()
                if (tokens == null) RefreshAttempt.Denied("test-denied") else RefreshAttempt.Granted(tokens)
            },
        )
        // returns WITHOUT the gate ever completing — direct proof the background refresh isn't awaited.
        assertEquals("grok-access", bearerToken(auth.credentials()))
        while (calls.get() == 0) yield() // observe the background call actually started
        gate.complete(GrokRefreshedTokens("new-access", "new-refresh", expiresIn = 21_600)) // let it finish cleanly
        assertEquals(1, calls.get())
        // Mirror of CodexAuthTest's post-completion wait: the unblocked refresh persists the
        // rotation after the assertions; waiting for the write keeps the mirrored idiom safe if
        // this temp dir ever becomes a JUnit-cleaned @TempDir (the codex twin's CI race).
        while (!file.readText().contains("new-access")) yield()
    }

    @Test
    fun `fully expired token with dead refresh yields null`() = runTest {
        val dir = Files.createTempDirectory("grok-dead")
        val now = 1_000_000L
        val file = authFile(dir, expiresAtMs = now - 1)
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { now },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
        )
        assertNull(auth.credentials())
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
        val auth = GrokAuthProvider(authPath = file, clock = { now }, refreshCall = {
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
        val auth = GrokAuthProvider(authPath = file, clock = { 1_000_000L }, refreshCall = { token ->
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
        val auth = GrokAuthProvider(authPath = file, clock = { 1_000_000L }, refreshCall = {
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
        val auth = GrokAuthProvider(authPath = file, clock = { 1_000_000L }, refreshCall = {
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
        val auth = GrokAuthProvider(authPath = file, clock = { 1_000_000L }, refreshCall = {
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
        val auth = GrokAuthProvider(authPath = file, clock = { 1_000_000L }, refreshCall = {
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
        val auth = GrokAuthProvider(authPath = file, clock = { now }, refreshCall = {
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
        val auth = GrokAuthProvider(authPath = file, clock = { now }, log = logs::add, refreshCall = {
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

// DR-73 (invariant audit): the persist-side merge re-read is the one credential parse DR-65 did
// not seal on grok — a file gone malformed between the pre-refresh read and the persist quoted
// its bytes through the raw throwable. The refreshCall corrupts the file mid-flow (the :311
// peer-rotation interleave idiom) so the merge re-read fails on real bytes.
class GrokMergeDiagnosticsTest {

    @Test
    fun `merge diagnostics never quote credential bytes - DR-73`() = runTest {
        val sentinel = "xai-SENTINEL-MERGE-LEAK"
        val dir = Files.createTempDirectory("grok-merge-leak")
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"tokens":{"access_token":"acc","refresh_token":"R1"}}""")
        val log = mutableListOf<String>()
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { 1_000_000L },
            refreshCall = {
                Files.writeString(file, """{"tokens":{"access_token":"$sentinel""")
                RefreshAttempt.Granted(splice.provider.grok.GrokRefreshedTokens("new-access", "new-refresh", 3600))
            },
            log = splice.core.util.LogSink { log += it },
        )
        auth.refresh()
        val joined = log.joinToString("\n")
        assertTrue(!joined.contains(sentinel), "credential bytes must never surface: $joined")
        assertTrue(log.any { it.contains("merge failed") }, "the merge degrade must log: $joined")
    }
}

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
        val auth = GrokAuthProvider(authPath = file, clock = { now }, refreshCall = {
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
        val auth = GrokAuthProvider(authPath = file, clock = { now }, refreshCall = {
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

// DR-151: BS-2's arm above proves the FALLBACK — a lost write still serves the current token — but
// says nothing about the LINE the operator reads on the way, which is where a leak would hide.
//
// This arm deliberately does NOT inject the write. An earlier draft added a
// `write: (Path, String) -> Unit` seam so a test could throw an arbitrary throwable; two walls
// rejected it and both were right. kt-no-lambda-seam bans the raw function type outright, and
// SH-10 requires the atomic 0600 primitive to be provably WHAT PERSISTS the credential — any
// injection point, fun interface or not, makes the real writer a runtime choice and reopens
// exactly the world-readable window #924 extracted SecureFile to make inexpressible. Trading a
// security invariant for test convenience on a credential write is the wrong direction, so the
// throwable here is one production actually produces: a denied parent directory.
//
// The withholding property itself lives at the sink and is proven there, with mutants, by
// PersistFailedRenderTest. What this arm adds is that the provider REACHES that sink, and that the
// line it produces never carries the credential.
class GrokPersistLinePrivacyTest {

    @Test
    fun `a failed persist logs its line without ever quoting the credential - DR-151`() = runTest {
        val dir = Files.createTempDirectory("grok-persist-line")
        val now = 1_000_000L
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"tokens":{"access_token":"grok-access","refresh_token":"grok-refresh"},
                "expires":${now + 10_000}}""",
        )
        val lines = mutableListOf<String>()
        val auth = GrokAuthProvider(
            authPath = file,
            clock = { now },
            nowIso = { "iso-now" },
            refreshCall = {
                RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "rotated-secret", expiresIn = 21_600))
            },
            log = LogSink { lines.add(it) },
        )
        Files.createFile(file.resolveSibling("${file.fileName}.lock"))
        val writable = Files.getPosixFilePermissions(file.parent)
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("r-xr-xr-x"))
        val served = try {
            auth.credentials()
        } finally {
            Files.setPosixFilePermissions(file.parent, writable)
        }
        assertEquals("grok-access", (served as Credentials.Bearer).token, "BS-2 fallback must still hold")

        val persist = lines.single { it.contains("persist failed") }
        assertFalse(persist.contains("rotated-secret"), "the rotated token must never reach the log: $persist")
        assertFalse(persist.contains("grok-refresh"), "nor the old refresh token: $persist")
        assertTrue(persist.contains("credential write failed"), "the typed Write branch must be what fired: $persist")
    }
}
