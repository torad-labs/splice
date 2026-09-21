// NEW: V4-14 Muse runtime-auth, key-mint, persistence, and exclusion walls.
package muse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.Credentials
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.provider.muse.MuseAuthProvider
import splice.provider.muse.MuseKeyMintCall
import splice.provider.muse.MuseMintAttempt
import splice.provider.muse.MuseMintMode
import splice.provider.muse.MuseSubscriptionKey
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialPresence
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_RATE_HOLD_MS = 60_000L
private const val MAX_MINT_HOLD_MS = 3_600_000L

class MuseAuthProviderTest {

    private fun authFile(
        dir: Path,
        accessToken: String = "account-access",
        apiKey: String = "persisted-key",
        extra: String = "",
    ): Path {
        val file = dir.resolve(".config").resolve("splice").resolve("auth").resolve("muse.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"access_token":"$accessToken","api_key":"$apiKey",
                "splice_auth_kind":"muse-oauth","splice_account_label":"backup"$extra}""",
        )
        return file
    }

    private fun provider(
        file: Path,
        logs: MutableList<String> = mutableListOf(),
        clock: WallClock = WallClock(System::currentTimeMillis),
        authCacheMs: Long = 30_000L,
        prefetchScope: kotlinx.coroutines.CoroutineScope? = null,
        mint: MuseKeyMintCall,
    ): MuseAuthProvider = MuseAuthProvider(
        authPath = file,
        log = LogSink { logs += it },
        clock = clock,
        mintCall = mint,
        authCacheMs = authCacheMs,
        prefetchScope = prefetchScope,
    )

    @Test
    fun `credentials serve the persisted bearer key without minting`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir)
        val calls = AtomicInteger()
        val auth = provider(file) { _, _ ->
            calls.incrementAndGet()
            MuseMintAttempt.Denied("must not be called")
        }

        repeat(3) {
            val credentials = auth.credentials()
            assertTrue(credentials is Credentials.Bearer)
            assertEquals("persisted-key", (credentials as Credentials.Bearer).token)
        }
        assertEquals(0, calls.get())
        assertEquals(file.toString(), auth.describe().fields["auth_path"])
        assertTrue(auth.allowRefreshAfterFailure(401, ""))
        assertTrue(auth.allowRefreshAfterFailure(403, "unauthenticated:bad-credentials"))
        assertFalse(auth.allowRefreshAfterFailure(403, "plan limit exceeded"))
        assertTrue(auth.allowRefreshAfterFailure(429, ""))
    }

    @Test
    fun `missing nested parent yields no credentials refresh or raw path diagnostic`(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("absent").resolve("nested").resolve("muse.json")
        val logs = mutableListOf<String>()
        val calls = AtomicInteger()
        val auth = provider(file, logs) { _, _ ->
            calls.incrementAndGet()
            MuseMintAttempt.Denied("unused")
        }

        assertNull(auth.credentials())
        assertNull(auth.refresh())
        assertEquals(0, calls.get())
        assertEquals(CredentialPresence.MISSING, auth.credentialEvidence().presence)
        assertNull(auth.credentialEvidence().identity)
        assertTrue(logs.any { it.contains("credential lock unavailable") })
        assertTrue(logs.none { it.contains(tempDir.toString()) })
    }

    @Test
    fun `refresh mints in refresh mode and merges metadata without dropping credential fields`(
        @TempDir tempDir: Path,
    ) = runTest {
        val file = authFile(
            tempDir,
            extra = ",\"vendor_future_field\":{\"nested\":true}",
        )
        val seen = mutableListOf<Pair<String, MuseMintMode>>()
        val logs = mutableListOf<String>()
        val auth = provider(file, logs) { accessToken, mode ->
            seen += accessToken to mode
            MuseMintAttempt.Granted(subscriptionKey("new-key"))
        }

        val refreshed = auth.refresh()

        assertTrue(refreshed is Credentials.Bearer)
        assertEquals("new-key", (refreshed as Credentials.Bearer).token)
        assertEquals(listOf("account-access" to MuseMintMode.REFRESH), seen)
        val written = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("account-access", written["access_token"]?.jsonPrimitive?.content)
        assertEquals("new-key", written["api_key"]?.jsonPrimitive?.content)
        assertEquals("muse-oauth", written["splice_auth_kind"]?.jsonPrimitive?.content)
        assertEquals("backup", written["splice_account_label"]?.jsonPrimitive?.content)
        assertTrue("vendor_future_field" in written, "unknown vendor field must survive: $written")
        assertTrue("subs_usage" in written)
        assertNull(written["user_email"])
        assertNull(written["user_id"])
        assertNull(written["payment_method"])
        assertNull(written["base_url"])
        assertTrue(logs.none { it.contains("account-access") || it.contains("new-key") })
    }

    @Test
    fun `a restarted provider reads the minted key without another mint`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir, apiKey = "old-key")
        val first = provider(file) { _, _ -> MuseMintAttempt.Granted(subscriptionKey("new-key")) }
        assertEquals("new-key", (first.refresh() as Credentials.Bearer).token)
        val calls = AtomicInteger()
        val restarted = provider(file) { _, _ ->
            calls.incrementAndGet()
            MuseMintAttempt.Denied("must not be called")
        }

        assertEquals("new-key", (restarted.credentials() as Credentials.Bearer).token)
        assertEquals(0, calls.get())
    }

    @Test
    fun `rate limited mint uses a bounded hold and keeps the persisted key`(@TempDir tempDir: Path) = runTest {
        var nowMs = 0L
        val defaultFile = authFile(tempDir.resolve("default"))
        val before = Files.readString(defaultFile)
        val defaultCalls = AtomicInteger()
        val defaultHold = provider(defaultFile, clock = WallClock { nowMs }) { _, _ ->
            defaultCalls.incrementAndGet()
            MuseMintAttempt.RateLimited()
        }

        repeat(70) { assertNull(defaultHold.refresh()) }
        assertEquals(1, defaultCalls.get())
        assertEquals(before, Files.readString(defaultFile))
        assertEquals("persisted-key", (defaultHold.credentials() as Credentials.Bearer).token)
        nowMs = DEFAULT_RATE_HOLD_MS
        assertNull(defaultHold.refresh())
        assertEquals(2, defaultCalls.get())

        nowMs = 0L
        val cappedFile = authFile(tempDir.resolve("capped"))
        val cappedCalls = AtomicInteger()
        val cappedHold = provider(cappedFile, clock = WallClock { nowMs }) { _, _ ->
            cappedCalls.incrementAndGet()
            MuseMintAttempt.RateLimited(MAX_MINT_HOLD_MS * 2)
        }
        assertNull(cappedHold.refresh())
        nowMs = MAX_MINT_HOLD_MS - 1L
        assertNull(cappedHold.refresh())
        assertEquals(1, cappedCalls.get())
        nowMs = MAX_MINT_HOLD_MS
        assertNull(cappedHold.refresh())
        assertEquals(2, cappedCalls.get())
    }

    @Test
    fun `inactive subscription holds repeated refreshes and exposes only a safe action origin`(
        @TempDir tempDir: Path,
    ) = runTest {
        var nowMs = 0L
        val file = authFile(tempDir)
        val logs = mutableListOf<String>()
        val calls = AtomicInteger()
        val auth = provider(file, logs, WallClock { nowMs }) { _, _ ->
            calls.incrementAndGet()
            MuseMintAttempt.SubscriptionRequired(
                "https://www.meta.ai/private/path?account=secret#fragment",
            )
        }

        repeat(70) { assertNull(auth.refresh()) }
        assertEquals(1, calls.get())
        assertNull(auth.credentials(), "a known inactive subscription must not keep serving its key")
        val description = auth.describe()
        assertTrue(description.present)
        assertEquals("inactive", description.fields["subscription"])
        assertEquals("https://www.meta.ai/", description.fields["action_url"])
        val subscriptionLogs = logs.filter { it.contains("subscription") }
        assertEquals(1, subscriptionLogs.size)
        assertTrue(subscriptionLogs.single().contains("https://www.meta.ai/"))
        assertFalse(subscriptionLogs.single().contains("account=secret"))

        nowMs = MAX_MINT_HOLD_MS
        assertNull(auth.refresh())
        assertEquals(2, calls.get())
    }

    @Test
    fun `credential recreation clears a stale inactive verdict and hold immediately`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir)
        val calls = AtomicInteger()
        val auth = provider(file) { accessToken, _ ->
            calls.incrementAndGet()
            if (accessToken == "replacement-account-token") {
                MuseMintAttempt.Granted(subscriptionKey("recovered-key"))
            } else {
                MuseMintAttempt.SubscriptionRequired("https://www.meta.ai/")
            }
        }
        assertNull(auth.refresh())
        assertNull(auth.credentials())

        authFile(
            tempDir,
            accessToken = "replacement-account-token",
            apiKey = "replacement-key-with-a-different-size",
            extra = ",\"replacement\":true",
        )

        assertEquals("recovered-key", (auth.refresh() as Credentials.Bearer).token)
        assertEquals(2, calls.get())
        assertEquals("recovered-key", (auth.credentials() as Credentials.Bearer).token)
        assertNull(auth.describe().fields["subscription"])
        assertNull(auth.describe().fields["action_url"])
    }

    @Test
    fun `successful remint after hold expiry clears the inactive verdict`(@TempDir tempDir: Path) = runTest {
        var nowMs = 0L
        val file = authFile(tempDir)
        var active = false
        val calls = AtomicInteger()
        val auth = provider(file, clock = WallClock { nowMs }) { _, _ ->
            calls.incrementAndGet()
            if (active) {
                MuseMintAttempt.Granted(subscriptionKey("restored-key"))
            } else {
                MuseMintAttempt.SubscriptionRequired("https://www.meta.ai/")
            }
        }
        assertNull(auth.refresh())
        assertNull(auth.credentials())
        active = true
        assertNull(auth.refresh(), "the inactive hold must suppress an immediate re-mint")
        assertEquals(1, calls.get())
        nowMs = MAX_MINT_HOLD_MS

        assertEquals("restored-key", (auth.refresh() as Credentials.Bearer).token)
        assertEquals(2, calls.get())
        assertEquals("restored-key", (auth.credentials() as Credentials.Bearer).token)
        assertNull(auth.describe().fields["subscription"])
    }

    @Test
    fun `missing or blank account token refuses explicit refresh without calling mint`(
        @TempDir tempDir: Path,
    ) = runTest {
        listOf(null, "", "   ").forEachIndexed { index, accountToken ->
            val file = tempDir.resolve("muse-$index.json")
            val access = accountToken?.let { "\"access_token\":\"$it\"," }.orEmpty()
            Files.writeString(file, "{$access\"api_key\":\"usable-key\"}")
            val calls = AtomicInteger()
            val logs = mutableListOf<String>()
            val auth = provider(file, logs) { _, _ ->
                calls.incrementAndGet()
                MuseMintAttempt.Denied("must not be called")
            }

            assertNull(auth.refresh())
            assertEquals(0, calls.get())
            assertEquals("usable-key", (auth.credentials() as Credentials.Bearer).token)
            assertTrue(logs.any { it.contains("account access token missing") })
            assertTrue(logs.none { it.contains("usable-key") })
        }
    }

    @Test
    fun `denial hold redacts diagnostics while invalid account token latches until recreation`(
        @TempDir tempDir: Path,
    ) = runTest {
        var nowMs = 0L
        val deniedFile = authFile(tempDir.resolve("denied"), accessToken = "account-secret")
        val logs = mutableListOf<String>()
        val deniedCalls = AtomicInteger()
        val denied = provider(deniedFile, logs, WallClock { nowMs }) { _, _ ->
            deniedCalls.incrementAndGet()
            MuseMintAttempt.Denied("provider echoed account-secret and api-key-secret")
        }

        repeat(2) { assertNull(denied.refresh()) }
        assertEquals(1, deniedCalls.get(), "a denied mint must hold repeated probes")
        nowMs = MAX_MINT_HOLD_MS
        assertNull(denied.refresh())
        assertEquals(2, deniedCalls.get())
        val surfaced = (logs + denied.describe().fields.values).joinToString("\n")
        assertFalse(surfaced.contains("account-secret"), surfaced)
        assertFalse(surfaced.contains("api-key-secret"), surfaced)
        assertTrue(logs.any { it.contains("key mint denied") })

        val invalidDir = tempDir.resolve("invalid")
        val invalidFile = authFile(invalidDir)
        val invalidCalls = AtomicInteger()
        val invalid = provider(invalidFile) { accessToken, _ ->
            invalidCalls.incrementAndGet()
            if (accessToken == "replacement-account-token") {
                MuseMintAttempt.Granted(subscriptionKey("replacement-key"))
            } else {
                MuseMintAttempt.InvalidAccountToken
            }
        }
        repeat(2) { assertNull(invalid.refresh()) }
        assertEquals(1, invalidCalls.get(), "the confirmed invalid account token must latch")
        assertEquals("persisted-key", (invalid.credentials() as Credentials.Bearer).token)

        authFile(
            invalidDir,
            accessToken = "replacement-account-token",
            apiKey = "replacement-persisted-key-with-a-different-size",
        )
        assertEquals("replacement-key", (invalid.refresh() as Credentials.Bearer).token)
        assertEquals(2, invalidCalls.get())
    }

    @Test
    fun `cancellation escapes refresh without a write or diagnostic`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir)
        val before = Files.readString(file)
        val logs = mutableListOf<String>()
        val auth = provider(file, logs) { _, _ ->
            throw CancellationException("cancelled mint")
        }
        var escaped = false

        try {
            auth.refresh()
        } catch (cancelled: CancellationException) {
            assertEquals("cancelled mint", cancelled.message)
            escaped = true
        }

        assertTrue(escaped)
        assertEquals(before, Files.readString(file))
        assertTrue(logs.isEmpty(), logs.toString())
    }

    private fun subscriptionKey(apiKey: String): MuseSubscriptionKey = MuseSubscriptionKey(
        apiKey = apiKey,
        fields = Json.parseToJsonElement(
            """{"api_key":"$apiKey","is_subs_active":true,"require_payment":false,
                "subs_tier_id":"pro","subs_tier_name":"Muse Pro","user_id":"42",
                "user_email":"operator@example.test","payment_method":"card",
                "base_url":"https://redirect.invalid/ignored",
                "splice_auth_kind":"attacker-kind","splice_account_label":"attacker-label",
                "subs_usage":{"weekly":{"used_percent":12,"resets_at":200}}}""",
        ).jsonObject,
    )
}
