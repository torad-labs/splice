// NEW: the 401 -> single-flight refresh -> reissue chain as a COMPOSITION. Every piece already had
// a test of its own — the refresh-trigger predicate (UpstreamClientAuthTest), SingleFlight,
// CredentialLock, and each provider's refresh (CodexAuthTest/KimiAuthProviderTest) — and nothing
// fed a real 401 from an upstream through planRetry into auth.refresh() and out the other side as a
// reissued request. That composition runs on every head at token expiry, and its live probe is
// blind by construction: heads-e2e.sh SKIPs a head that reports "not logged in", so a permanently
// broken refresh path reads as a clean skip forever.
//
// The invariants pinned here, none of which any single-piece test can see:
//   - the reissue does NOT spend a retry attempt (maxRetries = 1 and the turn still succeeds — if
//     the RETRY plan incremented `attempt`, the loop guard would eat the reissue and give up)
//   - the SECOND request carries the credential the refresh produced, not the stale one
//   - the perf row counts it honestly: attempts=2, retries=0, refreshes=1 (UP-004's counter block,
//     which the harness never reads, so this is its only oracle)
//   - the refresh is one-shot per post, and a refresh that yields nothing never retries at all
// MockEngine — no network.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.spi.PostContext
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A provider-shaped auth: [credentials] serves the CURRENT token and [refresh] rotates it, which
 *  is what every real provider's single-flight refresh does to its cache. [rotated] = null models a
 *  refresh that could not produce one (expired refresh token, revoked grant). */
private class RotatingAuth(private val rotated: String? = "tok-new") : RefreshableAuthProvider {
    val refreshCalls = AtomicInteger(0)
    private var token = "tok-old"

    override suspend fun credentials(): Credentials = Credentials.Bearer(token)

    override suspend fun refresh(): Credentials? {
        refreshCalls.incrementAndGet()
        val next = rotated ?: return null
        token = next
        return Credentials.Bearer(next)
    }

    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake", emptyMap())
}

class UpstreamClientRefreshChainTest {

    private val seenAuth = CopyOnWriteArrayList<String>()

    /** Records every request's Authorization header, then answers by [statuses] in order (the last
     *  entry repeats). Recording is the only way to prove WHICH credential rode the reissue. */
    private fun engineAnswering(vararg statuses: HttpStatusCode): MockEngine {
        val calls = AtomicInteger(0)
        return MockEngine { request ->
            seenAuth.add(request.headers["Authorization"].orEmpty())
            val at = calls.getAndIncrement()
            val status = statuses[minOf(at, statuses.size - 1)]
            if (status == HttpStatusCode.OK) {
                respond("fine", status, headersOf())
            } else {
                respond("""{"error":{"message":"token expired"}}""", status, headersOf())
            }
        }
    }

    // maxRetries = 1 IS the assertion: exactly one normal attempt is budgeted, so a second request
    // can only go out if the refresh reissue costs nothing from that budget.
    private fun clientOver(engine: MockEngine) = UpstreamClient(
        firstByteTimeoutMs = 5_000,
        totalTimeoutMs = 5_000,
        maxRetries = 1,
        client = HttpClient(engine),
        backoff = { _, _ -> error("the refresh path must never reach the backoff curve") },
    )

    private suspend fun postOnce(client: UpstreamClient, auth: RefreshableAuthProvider, perf: TurnPerf): String =
        client.post(
            PostContext(
                url = "https://api.example.test/v1",
                auth = auth,
                extraHeaders = { emptyMap() },
                perf = perf,
            ),
            "{}",
        ) { "ok" }

    @Test
    fun `a 401 refreshes once and reissues on the NEW credential without spending an attempt`() = runTest {
        val auth = RotatingAuth()
        val perf = TurnPerf()
        val engine = engineAnswering(HttpStatusCode.Unauthorized, HttpStatusCode.OK)

        assertEquals("ok", postOnce(clientOver(engine), auth, perf))

        assertEquals(listOf("Bearer tok-old", "Bearer tok-new"), seenAuth.toList())
        assertEquals(1, auth.refreshCalls.get(), "exactly one single-flight refresh per post")
        val counters = perf.snapshot().counters
        assertEquals(2L, counters[PerfKeys.ATTEMPTS], "both upstream calls are counted")
        assertEquals(0L, counters[PerfKeys.RETRIES] ?: 0L, "a refresh reissue is not a retry")
        assertEquals(1L, counters[PerfKeys.REFRESHES])
    }

    @Test
    fun `the single-flight refresh is one-shot - a second 401 gives up instead of refreshing again`() = runTest {
        val auth = RotatingAuth()
        val perf = TurnPerf()
        val engine = engineAnswering(HttpStatusCode.Unauthorized)

        val failure = assertThrows<UpstreamFailed> { postOnce(clientOver(engine), auth, perf) }

        assertEquals(401, failure.status)
        assertEquals(listOf("Bearer tok-old", "Bearer tok-new"), seenAuth.toList())
        assertEquals(1, auth.refreshCalls.get(), "the refreshedOnce budget bounds the loop at one")
        assertEquals(1L, perf.snapshot().counters[PerfKeys.REFRESHES])
    }

    @Test
    fun `a refresh that yields no credential surfaces the 401 without a second upstream call`() = runTest {
        val auth = RotatingAuth(rotated = null)
        val perf = TurnPerf()
        val engine = engineAnswering(HttpStatusCode.Unauthorized)

        val failure = assertThrows<UpstreamFailed> { postOnce(clientOver(engine), auth, perf) }

        assertEquals(401, failure.status)
        assertEquals(listOf("Bearer tok-old"), seenAuth.toList(), "nothing to reissue with: no second call")
        assertEquals(1, auth.refreshCalls.get())
        assertEquals(1L, perf.snapshot().counters[PerfKeys.ATTEMPTS])
    }
}
