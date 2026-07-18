// NEW: KimiAuthProvider runtime-auth pins — flat-file read yields ApiKey(x-api-key, empty prefix);
// proactive-refresh threshold math with a fake clock (before/after the max(300, expires_in/2)
// boundary); mandatory rotation persisted to disk at 0600; single-flight coalescing (two concurrent
// refreshes → one refreshCall). No network — refreshCall is injected (mirrors GrokProviderTest).
package kimi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
import splice.provider.kimi.KimiAuthProvider
import splice.provider.kimi.KimiRefreshedTokens
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger

class KimiAuthProviderTest {

    private fun authFile(
        dir: Path,
        access: String = "kimi-access",
        refresh: String = "kimi-refresh",
        expiresAtS: Long,
        expiresInS: Long = 3600,
    ): Path {
        val file = dir.resolve(".kimi").resolve("credentials").resolve("kimi-code.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"access_token":"$access","refresh_token":"$refresh","expires_at":$expiresAtS,
                "scope":"coding","token_type":"Bearer","expires_in":$expiresInS}""",
        )
        return file
    }

    @Test
    fun `flat file read yields ApiKey with x-api-key header and empty prefix`() = runTest {
        val dir = Files.createTempDirectory("kimi-read")
        val file = authFile(dir, access = "the-access", expiresAtS = Long.MAX_VALUE / 2)
        val auth = KimiAuthProvider(authPath = file, clock = { 1000L }, refreshCall = { null })
        val creds = auth.credentials()
        assertTrue(creds is Credentials.ApiKey)
        val api = creds as Credentials.ApiKey
        assertEquals("the-access", api.key)
        assertEquals("x-api-key", api.header)
        assertEquals("", api.prefix)
    }

    @Test
    fun `missing file yields null credentials`() = runTest {
        val dir = Files.createTempDirectory("kimi-missing")
        val auth = KimiAuthProvider(authPath = dir.resolve("nope.json"), refreshCall = { null })
        assertNull(auth.credentials())
    }

    @Test
    fun `describe is masked kimi-oauth with device login`() = runTest {
        val dir = Files.createTempDirectory("kimi-desc")
        val file = authFile(dir, expiresAtS = Long.MAX_VALUE / 2)
        val desc = KimiAuthProvider(authPath = file, refreshCall = { null }).describe()
        assertTrue(desc.present)
        assertEquals("kimi-oauth", desc.kind)
        assertEquals("device", desc.fields["login"])
        assertEquals(file.toString(), desc.fields["auth_path"])
        assertTrue(desc.fields.values.none { it.contains("kimi-access") || it.contains("kimi-refresh") })
    }

    // threshold = max(300, expires_in/2). expires_in = 1000 -> threshold = 500.
    // now = 1_000_000_000s. token valid for 600s ahead -> NOT within threshold -> no refresh.
    @Test
    fun `token comfortably before the proactive threshold is served without refresh`() = runTest {
        val dir = Files.createTempDirectory("kimi-noref")
        val nowMs = 1_000_000_000_000L
        val nowS = nowMs / 1000
        val file = authFile(dir, access = "current", expiresAtS = nowS + 600, expiresInS = 1000)
        val calls = AtomicInteger(0)
        val auth = KimiAuthProvider(
            authPath = file,
            clock = { nowMs },
            refreshCall = {
                calls.incrementAndGet()
                null
            },
        )
        assertEquals("current", (auth.credentials() as Credentials.ApiKey).key)
        assertEquals(0, calls.get())
    }

    // token valid for only 400s ahead (< 500 threshold) -> proactive refresh fires.
    @Test
    fun `token within the proactive threshold triggers refresh and serves the rotated token`() = runTest {
        val dir = Files.createTempDirectory("kimi-ref")
        val nowMs = 1_000_000_000_000L
        val nowS = nowMs / 1000
        val file = authFile(dir, access = "current", expiresAtS = nowS + 400, expiresInS = 1000)
        val calls = AtomicInteger(0)
        val auth = KimiAuthProvider(
            authPath = file,
            clock = { nowMs },
            refreshCall = {
                calls.incrementAndGet()
                KimiRefreshedTokens(accessToken = "rotated-access", refreshToken = "rotated-refresh", expiresIn = 3600)
            },
        )
        assertEquals("rotated-access", (auth.credentials() as Credentials.ApiKey).key)
        assertEquals(1, calls.get())
        // rotation persisted to disk, expires_at recomputed = now/1000 + 3600
        val onDisk = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("rotated-access", onDisk["access_token"]?.jsonPrimitive?.content)
        assertEquals("rotated-refresh", onDisk["refresh_token"]?.jsonPrimitive?.content)
        assertEquals("${nowS + 3600}", onDisk["expires_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun `expired token with a failing refresh yields null`() = runTest {
        val dir = Files.createTempDirectory("kimi-expired")
        val nowMs = 1_000_000_000_000L
        val nowS = nowMs / 1000
        val file = authFile(dir, expiresAtS = nowS - 10, expiresInS = 1000)
        val auth = KimiAuthProvider(authPath = file, clock = { nowMs }, refreshCall = { null })
        assertNull(auth.credentials())
    }

    @Test
    fun `not-yet-expired token with a failing refresh still serves the current token`() = runTest {
        val dir = Files.createTempDirectory("kimi-degrade")
        val nowMs = 1_000_000_000_000L
        val nowS = nowMs / 1000
        // within threshold (400 < 500) so refresh is attempted, but token not yet expired.
        val file = authFile(dir, access = "still-valid", expiresAtS = nowS + 400, expiresInS = 1000)
        val auth = KimiAuthProvider(authPath = file, clock = { nowMs }, refreshCall = { null })
        assertEquals("still-valid", (auth.credentials() as Credentials.ApiKey).key)
    }

    @Test
    fun `refresh writes the auth file with 0600 permissions`() = runTest {
        val dir = Files.createTempDirectory("kimi-perms")
        val file = authFile(dir, expiresAtS = 0L)
        val auth = KimiAuthProvider(
            authPath = file,
            refreshCall = { KimiRefreshedTokens("a", "r", 3600) },
        )
        auth.refresh()
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
        )
    }

    @Test
    fun `two concurrent refreshes coalesce to a single refreshCall`() = runBlocking {
        val dir = Files.createTempDirectory("kimi-sf")
        val file = authFile(dir, expiresAtS = 0L)
        val calls = AtomicInteger(0)
        val proceed = CompletableDeferred<Unit>()
        val auth = KimiAuthProvider(
            authPath = file,
            refreshCall = {
                calls.incrementAndGet()
                proceed.await()
                KimiRefreshedTokens("a", "r", 3600)
            },
        )
        val a = launch { auth.refresh() }
        val b = launch { auth.refresh() }
        while (calls.get() == 0) yield() // first refresh block has entered
        repeat(1000) { yield() } // let the second caller reach the mutex and coalesce
        proceed.complete(Unit)
        a.join()
        b.join()
        assertEquals(1, calls.get())
    }
}
