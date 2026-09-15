// NEW: V4-22 review fixes for mint holds, prefetch lifecycle, cache, and transport failures.
package muse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.Credentials
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.provider.muse.MuseAuthProvider
import splice.provider.muse.MuseCredentialSnapshot
import splice.provider.muse.MuseKeyMintCall
import splice.provider.muse.MuseMintAttempt
import splice.provider.muse.MuseMintHolds
import splice.provider.muse.MuseSubscriptionKey
import splice.spi.ProcessDispatchers
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

private const val DEFAULT_RATE_HOLD_MS = 60_000L
private const val MAX_MINT_HOLD_MS = 3_600_000L
private const val HANG_BACKSTOP_S = 60L

class MuseAuthProviderFixesTest {

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
        clock: WallClock = WallClock(System::currentTimeMillis),
        authCacheMs: Long = 30_000L,
        prefetchScope: kotlinx.coroutines.CoroutineScope? = null,
        flightContext: CoroutineContext = ProcessDispatchers().background(),
        mint: MuseKeyMintCall,
    ): MuseAuthProvider = MuseAuthProvider(
        authPath = file,
        log = LogSink { },
        clock = clock,
        mintCall = mint,
        authCacheMs = authCacheMs,
        prefetchScope = prefetchScope,
        flightContext = flightContext,
    )

    @Test
    fun `RateLimited zero still floors to the default hold`(@TempDir tempDir: Path) = runTest {
        var nowMs = 0L
        val file = authFile(tempDir)
        val calls = AtomicInteger()
        val auth = provider(file, clock = WallClock { nowMs }) { _, _ ->
            calls.incrementAndGet()
            MuseMintAttempt.RateLimited(0L)
        }
        assertNull(auth.refresh())
        assertEquals(1, calls.get())
        nowMs = DEFAULT_RATE_HOLD_MS - 1L
        assertNull(auth.refresh())
        assertEquals(1, calls.get())
        nowMs = DEFAULT_RATE_HOLD_MS
        assertNull(auth.refresh())
        assertEquals(2, calls.get())
    }

    @Test
    fun `mint transport failure records the default hold`(@TempDir tempDir: Path) = runTest {
        var nowMs = 0L
        val file = authFile(tempDir)
        val before = Files.readString(file)
        val calls = AtomicInteger()
        val auth = provider(file, clock = WallClock { nowMs }) { _, _ ->
            calls.incrementAndGet()
            throw java.io.IOException("connection reset")
        }
        assertNull(auth.refresh())
        assertEquals(1, calls.get())
        assertEquals(before, Files.readString(file))
        nowMs = DEFAULT_RATE_HOLD_MS - 1L
        assertNull(auth.refresh())
        assertEquals(1, calls.get())
        nowMs = DEFAULT_RATE_HOLD_MS
        assertNull(auth.refresh())
        assertEquals(2, calls.get())
    }

    @Test
    fun `credentials cache hits until identity or hold invalidates it`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir, apiKey = "token-AAAAAAA")
        val auth = provider(file, clock = WallClock { 1_000L }, authCacheMs = 600_000L) { _, _ ->
            MuseMintAttempt.Denied("must not mint")
        }
        assertEquals("token-AAAAAAA", (auth.credentials() as Credentials.Bearer).token)

        val stamp = Files.getLastModifiedTime(file)
        authFile(tempDir, apiKey = "token-BBBBBBB")
        Files.setLastModifiedTime(file, stamp)
        assertEquals("token-AAAAAAA", (auth.credentials() as Credentials.Bearer).token)

        authFile(tempDir, apiKey = "token-CCCCCCCC-longer")
        Files.setLastModifiedTime(file, stamp)
        assertEquals("token-CCCCCCCC-longer", (auth.credentials() as Credentials.Bearer).token)

        val held = provider(file, clock = WallClock { 1_000L }, authCacheMs = 600_000L) { _, _ ->
            MuseMintAttempt.RateLimited(0L)
        }
        assertEquals("token-CCCCCCCC-longer", (held.credentials() as Credentials.Bearer).token)
        val heldStamp = Files.getLastModifiedTime(file)
        assertNull(held.refresh())
        authFile(tempDir, apiKey = "token-DDDDDDDD-longer")
        Files.setLastModifiedTime(file, heldStamp)
        assertEquals("token-DDDDDDDD-longer", (held.credentials() as Credentials.Bearer).token)
    }

    @Test
    fun `cancelling prefetchScope cancels an in-flight mint and writes nothing`(
        @TempDir tempDir: Path,
    ) = runTest {
        val file = authFile(tempDir)
        val before = Files.readString(file)
        val job = SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(job)
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val auth = provider(file, prefetchScope = scope) { _, _ ->
            entered.complete(Unit)
            try {
                proceed.await()
            } catch (_: CancellationException) {
            }
            MuseMintAttempt.Granted(
                MuseSubscriptionKey(
                    apiKey = "after-stop",
                    fields = Json.parseToJsonElement("{}").jsonObject,
                ),
            )
        }
        val refresh = launch {
            try {
                auth.refresh()
            } catch (_: CancellationException) {
            }
        }
        entered.await()
        scope.cancel()
        proceed.complete(Unit)
        refresh.join()
        assertEquals(before, Files.readString(file))
    }

    @Test
    fun `stale readers cannot clear a newer credential hold`() {
        val holds = MuseMintHolds(WallClock { 0L })
        val fields = JsonObject(emptyMap())
        val old = MuseCredentialSnapshot(
            accessToken = "old-account-token",
            apiKey = "old-key",
            fields = fields,
            identity = CredentialFileIdentity(1L, 10L),
        )
        val current = MuseCredentialSnapshot(
            accessToken = "current-account-token",
            apiKey = "current-key",
            fields = fields,
            identity = CredentialFileIdentity(2L, 20L),
        )
        holds.recordInactive(current, MAX_MINT_HOLD_MS, "https://www.meta.ai/")

        assertFalse(holds.blocksCredentials(old))
        assertFalse(holds.suppresses(old))
        assertTrue(holds.blocksCredentials(current))
        assertTrue(holds.suppresses(current))
    }

    @Test
    fun `usageFields returns only subs_usage and never persists`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir)
        val before = Files.readString(file)
        val calls = AtomicInteger()
        val auth = provider(file) { _, _ ->
            calls.incrementAndGet()
            MuseMintAttempt.Granted(usageKey("must-not-be-written"))
        }
        val fields = auth.usageFields()
        assertEquals(1, calls.get())
        assertNull(fields!!["api_key"])
        val weekly = fields.getValue("weekly").jsonObject
        assertEquals(12.0, weekly.getValue("used_percent").jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(before, Files.readString(file))
        assertEquals("persisted-key", (auth.credentials() as Credentials.Bearer).token)
    }

    @Test
    fun `usageFields obeys an existing rate hold with no POST`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir)
        val calls = AtomicInteger()
        val auth = provider(file, clock = WallClock { 0L }) { _, _ ->
            calls.incrementAndGet()
            MuseMintAttempt.RateLimited()
        }
        assertNull(auth.refresh())
        assertEquals(1, calls.get())
        assertNull(auth.usageFields())
        assertEquals(1, calls.get())
    }

    @Test
    fun `usageFields 429 records the retry hold so refresh does not POST`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir)
        val before = Files.readString(file)
        val calls = AtomicInteger()
        val auth = provider(file, clock = WallClock { 0L }) { _, _ ->
            calls.incrementAndGet()
            MuseMintAttempt.RateLimited(DEFAULT_RATE_HOLD_MS)
        }
        assertNull(auth.usageFields())
        assertEquals(1, calls.get())
        assertEquals(before, Files.readString(file))
        repeat(10) { assertNull(auth.refresh()) }
        assertEquals(1, calls.get())
    }

    @Test
    fun `usageFields inactive records the hold without persisting`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir)
        val before = Files.readString(file)
        val calls = AtomicInteger()
        val auth = provider(file, clock = WallClock { 0L }) { _, _ ->
            calls.incrementAndGet()
            MuseMintAttempt.SubscriptionRequired("https://www.meta.ai/")
        }
        assertNull(auth.usageFields())
        assertEquals(1, calls.get())
        assertEquals(before, Files.readString(file))
        assertNull(auth.credentials())
        assertEquals("inactive", auth.describe().fields["subscription"])
        repeat(10) { assertNull(auth.usageFields()) }
        assertEquals(1, calls.get())
    }

    @Timeout(HANG_BACKSTOP_S)
    @Test
    fun `two concurrent refreshes coalesce to one mint`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val auth = provider(file, flightContext = coroutineContext) { _, _ ->
            calls.incrementAndGet()
            entered.complete(Unit)
            proceed.await()
            MuseMintAttempt.Granted(usageKey("coalesced-key"))
        }
        val first = launch { auth.refresh() }
        val second = launch { auth.refresh() }
        entered.await()
        repeat(100) { yield() }
        proceed.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, calls.get())
        assertEquals("coalesced-key", (auth.credentials() as Credentials.Bearer).token)
    }

    @Test
    fun `granted mint does not overwrite a concurrently replaced login`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir)
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val auth = provider(file) { _, _ ->
            entered.complete(Unit)
            proceed.await()
            MuseMintAttempt.Granted(usageKey("stale-minted-key"))
        }
        val refresh = async { auth.refresh() }
        entered.await()
        authFile(
            tempDir,
            accessToken = "replacement-account-token",
            apiKey = "replacement-api-key-with-a-different-size",
            extra = ",\"replacement_unknown\":{\"nested\":true}",
        )
        proceed.complete(Unit)
        assertNull(refresh.await())
        val current = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("replacement-account-token", current["access_token"]?.jsonPrimitive?.content)
        assertEquals("replacement-api-key-with-a-different-size", current["api_key"]?.jsonPrimitive?.content)
        assertTrue(current.containsKey("replacement_unknown"))
    }

    @Test
    fun `granted mint does not recreate a credential deleted while minting`(
        @TempDir tempDir: Path,
    ) = runTest {
        val file = authFile(tempDir)
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val auth = provider(file) { _, _ ->
            entered.complete(Unit)
            proceed.await()
            MuseMintAttempt.Granted(usageKey("orphaned-key"))
        }
        val refresh = async { auth.refresh() }
        entered.await()
        Files.delete(file)
        proceed.complete(Unit)
        assertNull(refresh.await())
        assertFalse(Files.exists(file))
    }

    @Test
    fun `usageFields Granted does not clear an inactive verdict`(@TempDir tempDir: Path) = runTest {
        var nowMs = 0L
        val file = authFile(tempDir)
        val calls = AtomicInteger()
        val auth = provider(file, clock = WallClock { nowMs }) { _, _ ->
            calls.incrementAndGet()
            if (nowMs == 0L) {
                MuseMintAttempt.SubscriptionRequired("https://www.meta.ai/")
            } else {
                MuseMintAttempt.Granted(usageKey("must-not-unlock"))
            }
        }
        assertNull(auth.usageFields())
        assertEquals(1, calls.get())
        assertEquals("inactive", auth.describe().fields["subscription"])
        nowMs = MAX_MINT_HOLD_MS + 1
        val fields = auth.usageFields()
        assertEquals(2, calls.get())
        assertTrue(fields != null)
        assertNull(auth.credentials())
        assertEquals("inactive", auth.describe().fields["subscription"])
    }

    @Timeout(HANG_BACKSTOP_S)
    @Test
    fun `a concurrent poll tick and refresh produce one mint`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val auth = provider(file, flightContext = coroutineContext) { _, _ ->
            calls.incrementAndGet()
            entered.complete(Unit)
            proceed.await()
            MuseMintAttempt.Granted(usageKey("shared-mint"))
        }
        val refresh = launch { auth.refresh() }
        val poll = launch { auth.usageFields() }
        entered.await()
        repeat(100) { yield() }
        proceed.complete(Unit)
        refresh.join()
        poll.join()
        assertEquals(1, calls.get())
    }

    @Timeout(HANG_BACKSTOP_S)
    @Test
    fun `a poll mint for token A does not latch a refresh on token B`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir, accessToken = "token-a", apiKey = "key-a")
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val auth = provider(file, flightContext = coroutineContext) { _, _ ->
            val n = calls.incrementAndGet()
            if (n == 1) {
                entered.complete(Unit)
                proceed.await()
                MuseMintAttempt.InvalidAccountToken
            } else {
                MuseMintAttempt.Granted(usageKey("key-b"))
            }
        }
        val poll = launch { auth.usageFields() }
        entered.await()
        assertEquals(1, calls.get(), "refresh must not start until the poll mint has entered")
        proceed.complete(Unit)
        poll.join()
        authFile(tempDir, accessToken = "token-b", apiKey = "key-b")
        auth.refresh()
        assertNull(auth.describe().fields["account_token"])
        assertEquals("key-b", (auth.credentials() as Credentials.Bearer).token)
        assertEquals(2, calls.get())
    }

    @Test
    fun `unknown mint-body fields drop while on-disk unknowns survive`(@TempDir tempDir: Path) = runTest {
        val file = authFile(tempDir, extra = ",\"vendor_future_field\":{\"nested\":true}")
        val auth = provider(file) { _, _ ->
            MuseMintAttempt.Granted(
                MuseSubscriptionKey(
                    apiKey = "new-key",
                    fields = Json.parseToJsonElement(
                        """{"api_key":"new-key","is_subs_active":true,"require_payment":false,
                            "future_vendor_flag":true,
                            "subs_usage":{"weekly":{"used_percent":1,"resets_at":1}}}""",
                    ).jsonObject,
                ),
            )
        }
        auth.refresh()
        val written = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("new-key", written["api_key"]?.jsonPrimitive?.content)
        assertTrue(written.containsKey("vendor_future_field"))
        assertFalse(written.containsKey("future_vendor_flag"))
    }

    private fun usageKey(apiKey: String): MuseSubscriptionKey = MuseSubscriptionKey(
        apiKey = apiKey,
        fields = Json.parseToJsonElement(
            """{"api_key":"$apiKey","is_subs_active":true,"require_payment":false,
                "subs_usage":{"weekly":{"used_percent":12,"resets_at":200}}}""",
        ).jsonObject,
    )
}
