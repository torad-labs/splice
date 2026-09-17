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

// V4-70: the three fixtures below are TOP-LEVEL rather than members of the test class because a
// second class in this file needs them and duplicating them would let the two copies drift — the
// same failure this campaign keeps meeting in other guises. Test sources are outside the
// no-top-level-functions wall's scope (it reads gateway/*/src/main), which is what makes this the
// cheapest correct shape; the alternative was a new file, outside the row's fence.
internal fun authFile(
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

internal fun provider(
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

internal fun usageKey(apiKey: String): MuseSubscriptionKey = MuseSubscriptionKey(
    apiKey = apiKey,
    fields = Json.parseToJsonElement(
        """{"api_key":"$apiKey","is_subs_active":true,"require_payment":false,
            "subs_usage":{"weekly":{"used_percent":12,"resets_at":200}}}""",
    ).jsonObject,
)

class MuseAuthProviderFixesTest {

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

        // V4-70 CHANGED WHAT THIS ASSERTS, deliberately, because the cache was serving a credential
        // that no longer existed on disk. The store's freshness check is `cached.identity ==
        // identity` (MuseCredentialStore.read), and the identity now carries the file's CONTENT, so a
        // same-size rewrite with a restored timestamp is a MISS where it used to be a stale HIT.
        // That is the same defect the latch had — metadata standing in for content — so it is fixed
        // in both places rather than pinned in one. An untouched file is still a HIT, which is what
        // the cache exists for:
        val stamp = Files.getLastModifiedTime(file)
        assertEquals("token-AAAAAAA", (auth.credentials() as Credentials.Bearer).token)

        authFile(tempDir, apiKey = "token-BBBBBBB")
        Files.setLastModifiedTime(file, stamp)
        assertEquals(
            "token-BBBBBBB",
            (auth.credentials() as Credentials.Bearer).token,
            "a same-length rewrite is now DETECTED: the cache must not serve a credential that is gone",
        )

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
            identity = CredentialFileIdentity(1L, 10L, "digest-of-the-old-credential"),
        )
        val current = MuseCredentialSnapshot(
            accessToken = "current-account-token",
            apiKey = "current-key",
            fields = fields,
            identity = CredentialFileIdentity(2L, 20L, "digest-of-the-current-credential"),
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
}

/** V4-70: the identity collision, in its own class for the same reason V4-63 and V4-68 split
 *  theirs — MuseAuthProviderFixesTest sits at detekt's LargeClass ceiling and a new pin there
 *  reddens the gate before it can prove anything. The fixtures it needs are the top-level ones
 *  above, which is why they were lifted out of the other class. */
class MuseCredentialIdentityCollisionTest {

    // ── V4-70: THE COLLISION, CONSTRUCTED RATHER THAN AWAITED ────────────────────────────────────
    // The poll-mint test above is the one-in-N witness of this bug: it reds only when the rewrite
    // happens to land in the same millisecond as the file the latch was armed against, which is why
    // it read as a flake. This test CONSTRUCTS that collision with Files.setLastModifiedTime, so it
    // is red on every run against a metadata-only identity and green once the content is part of it.
    // Both are kept: this one is the proof, that one is the real-world path that found it.
    //
    // The operator story is DR-176's, already suffered once in production: a credential is rejected,
    // the operator re-authenticates, and every turn is then refused LOCALLY — no request leaves the
    // box — because the sentinel outlived the credential it was armed against. DR-176 widened the
    // identity with [sizeBytes], which a same-length rewrite does not change; content does.
    @Test
    fun `a same-length rewrite that keeps the mtime still clears the account latch - V4-70`(
        @TempDir tempDir: Path,
    ) = runTest {
        val file = authFile(tempDir, accessToken = "token-a", apiKey = "key-a")
        val calls = AtomicInteger()
        val auth = provider(file) { _, _ ->
            if (calls.incrementAndGet() == 1) {
                MuseMintAttempt.InvalidAccountToken
            } else {
                MuseMintAttempt.Granted(usageKey("key-b"))
            }
        }

        // Arm the latch through the real path: the mint rejects the token the file still holds.
        auth.refresh()
        assertEquals(
            "invalid",
            auth.describe().fields["account_token"],
            "precondition: the rejected mint must have latched the account as invalid",
        )

        val originalMtime = Files.getLastModifiedTime(file)

        // The operator re-authenticates: a DIFFERENT credential of the SAME LENGTH, with the
        // timestamp restored — what cp -p, a tar extract or any mtime-preserving sync produces.
        authFile(tempDir, accessToken = "token-b", apiKey = "key-b")
        Files.setLastModifiedTime(file, originalMtime)

        auth.refresh()
        assertNull(
            auth.describe().fields["account_token"],
            "a NEW credential must clear the latch even when its metadata matches the rejected one",
        )
        assertEquals(
            "key-b",
            (auth.credentials() as Credentials.Bearer).token,
            "and the re-authenticated credential must actually be serving",
        )
    }
}
