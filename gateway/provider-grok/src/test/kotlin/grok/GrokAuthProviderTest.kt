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
import org.junit.jupiter.api.Timeout
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
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
        val auth = GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { now }, refreshCall = {
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
            authCacheMs = 30_000L,
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
        val auth = GrokAuthProvider(authPath = file, authCacheMs = 30_000L, clock = { now }, refreshCall = {
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
            authCacheMs = 30_000L,
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
            authCacheMs = 30_000L,
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
            authCacheMs = 30_000L,
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
            authCacheMs = 30_000L,
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
            authCacheMs = 30_000L,
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
            authCacheMs = 30_000L,
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
            authCacheMs = 30_000L,
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
            authCacheMs = 30_000L,
            clock = { now },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
        )
        assertNull(auth.credentials())
    }
}

// V4-152: GrokMergeDiagnosticsTest MOVED to its own file, GrokMergeDiagnosticsTest.kt. It was
// already a separate class with its own JUnit report; only the file was wrong.

// V4-152: GrokPeerRotationExpiryTest MOVED to its own file, GrokPeerRotationExpiryTest.kt.

// V4-152: GrokBackoffExpiryTest MOVED to its own file, GrokBackoffExpiryTest.kt, carrying its two
// private helpers with it.

// V4-152: GrokTornReadCacheTest MOVED to its own file, GrokTornReadCacheTest.kt. That completes the
// relocation half of this row: the six classes that were sharing this file each live in their own
// file now, and GrokAuthProviderTest.kt holds only GrokAuthProviderTest.
