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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.provider.grok.GrokAuthProvider
import splice.provider.grok.GrokRefreshedTokens
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText

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

    // G17: proves the prefetch tier is truly fire-and-forget on a real dispatcher — if credentials()
    // still awaited the refresh synchronously, this would deadlock/timeout on the un-completed gate.
    // Mirrors KimiAuthProviderTest's "two concurrent refreshes coalesce" idiom (runBlocking, not
    // runTest, for deterministic real-dispatcher async proof).
    @Test
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
    fun `refresh response without expires_in keeps the old expires field`() = runTest {
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
        assertEquals(oldExpires, onDisk["expires"]!!.jsonPrimitive.content.toLong())
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
}
