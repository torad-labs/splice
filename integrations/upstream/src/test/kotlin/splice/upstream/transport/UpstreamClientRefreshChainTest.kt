// NEW: the 401 -> single-flight refresh -> reissue chain as a COMPOSITION. Every piece already had
// a test of its own — the refresh-trigger predicate (UpstreamClientAuthTest), SingleFlight,
// CredentialLock, and each provider's refresh (CodexAuthTest/KimiAuthProviderTest) — and nothing
// fed a real 401 from an upstream through RetryEngine into auth.refresh() and out the other side as a
// reissued request. That composition runs on every head at token expiry, and its live probe is
// blind by construction: `e2e heads` SKIPs a head that reports "not logged in", so a permanently
// broken refresh path reads as a clean skip forever.
//
// The invariants pinned here, none of which any single-piece test can see:
//   - the reissue spends no retry of its own: RetryEngine's REFRESH_LOGIN step spends nothing and waits
//     nothing, so the renewed request goes out at once and the perf row counts no retry
//   - the SECOND request carries the credential the refresh produced, not the stale one
//   - the perf row counts it honestly: attempts=2, retries=0, refreshes=1 (UP-004's counter block,
//     which the harness never reads, so this is its only oracle)
//   - the refresh is one-shot per post, and a refresh that yields nothing never retries at all
// MockEngine — no network.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.Waiter
import splice.upstream.credentials.RenewalStage
import splice.upstream.credentials.TokenRenewal
import splice.upstream.failure.Replies
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import splice.upstream.testing.OpenAiTestReplies as OpenAiReplies

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

/** A login renewed from [file] through the shared TokenRenewal, as codex, grok, kimi and Claude accounts are. A lookup
 *  reads the file, or [staleRead] once, as one that read it before a renewal landed. With [renewedElsewhereFirst], the
 *  first lookup holds the file's expired token and its renewal cannot answer, while another turn's renewal of that
 *  token lands. [spent] is every refresh token the endpoint was asked to spend. */
private class FileLogin(private val file: Path) : RefreshableAuthProvider {
    val spent = CopyOnWriteArrayList<String>()
    var staleRead: String? = null
    var renewedElsewhereFirst = false

    private val renewal = TokenRenewal(
        file,
        "test",
        WallClock { 0L },
        LogSink { },
        null,
        RenewalStage { prior ->
            val onFile = Files.readString(file)
            if (prior != null && onFile != prior) {
                RefreshOutcome.Refreshed(Credentials.Bearer(onFile))
            } else {
                spent += onFile
                val granted = "tok-${spent.size}"
                Files.writeString(file, granted)
                RefreshOutcome.Refreshed(Credentials.Bearer(granted))
            }
        },
    )

    override suspend fun answer(): LoginAnswer {
        if (renewedElsewhereFirst) {
            renewedElsewhereFirst = false
            val held = Credentials.Bearer(Files.readString(file))
            renewal.exchange(held)
            return LoginAnswer.Unrenewed(RefreshOutcome.Unavailable("token endpoint busy"), held)
        }
        val read = staleRead?.also { staleRead = null } ?: Files.readString(file)
        return renewal.serve(Credentials.Bearer(read))
    }

    override suspend fun credentials(): Credentials? = (answer() as? LoginAnswer.Granted)?.credentials

    override suspend fun refresh(): Credentials? = (exchange() as? RefreshOutcome.Refreshed)?.credentials

    override suspend fun exchange(): RefreshOutcome = renewal.exchange()

    override suspend fun exchange(refused: Credentials?): RefreshOutcome = renewal.exchange(refused)

    override suspend fun describe(): AuthDescription = AuthDescription(true, "file", emptyMap())
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

    // No wait IS the assertion: the renewed request goes out at once, so any wait fails the test.
    private fun clientOver(engine: MockEngine) = UpstreamClient(
        totalTimeoutMs = 5_000,
        client = HttpClient(engine),
        pacing = RetryPacing(waiter = Waiter { error("the refresh path must never wait") }),
    )

    private suspend fun postOnce(client: UpstreamClient, auth: RefreshableAuthProvider, perf: TurnPerf): String =
        client.posted(
            PostContext(
                url = "https://api.example.test/v1",
                auth = auth,
                extraHeaders = { emptyMap() },
                replies = Replies(OpenAiReplies),
                observers = PostObservers(perf = perf),
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

        val failure = assertEnds<UpstreamFailed> { postOnce(clientOver(engine), auth, perf) }

        assertEquals(401, failure.evidence.status)
        assertEquals(listOf("Bearer tok-old", "Bearer tok-new"), seenAuth.toList())
        assertEquals(1, auth.refreshCalls.get(), "one renewal per login per turn bounds the loop at one")
        assertEquals(1L, perf.snapshot().counters[PerfKeys.REFRESHES])
    }

    // Nov 10, 2026: the 401 path asked the renewal about the token this login served last. Another turn served the
    // renewed token while this one's request with the old token was in flight, so this turn's 401 rotated the renewed
    // token as well, spending a refresh token no one had to spend.
    @Test
    fun `a 401 for a token another turn already renewed resends the renewal and spends no refresh token`(
        @TempDir dir: Path,
    ) = runTest {
        val file = dir.resolve("auth.json")
        Files.writeString(file, "tok-old")
        val login = FileLogin(file)
        var anotherTurnLooksUp = false
        val engine = MockEngine { request ->
            val sent = request.headers["Authorization"].orEmpty()
            seenAuth.add(sent)
            if (anotherTurnLooksUp) {
                anotherTurnLooksUp = false
                login.answer()
            }
            if (sent == "Bearer tok-old") {
                respond("""{"error":{"message":"token expired"}}""", HttpStatusCode.Unauthorized, headersOf())
            } else {
                respond("fine", HttpStatusCode.OK, headersOf())
            }
        }
        val client = clientOver(engine)

        assertEquals("ok", postOnce(client, login, TurnPerf()))
        // The second turn read the file before the first turn's renewal landed, and while its request is in flight a
        // third turn's lookup serves the renewed token.
        login.staleRead = "tok-old"
        anotherTurnLooksUp = true
        assertEquals("ok", postOnce(client, login, TurnPerf()))

        assertEquals(listOf("Bearer tok-old", "Bearer tok-1", "Bearer tok-old", "Bearer tok-1"), seenAuth.toList())
        assertEquals(listOf("tok-old"), login.spent, "only the old refresh token was ever spent")
    }

    // The same race on a login whose token expired before anything was sent: the lookup's renewal could not answer, and
    // the turn's exchange 2 s later was asked about the token served last, which another turn's renewal had just made
    // the renewed one.
    @Test
    fun `an expired login renewed by another turn meanwhile is adopted, not rotated again`(@TempDir dir: Path) =
        runTest {
            val file = dir.resolve("auth.json")
            Files.writeString(file, "tok-old")
            val login = FileLogin(file)
            login.renewedElsewhereFirst = true
            val engine = MockEngine { request ->
                seenAuth.add(request.headers["Authorization"].orEmpty())
                respond("fine", HttpStatusCode.OK, headersOf())
            }
            val client = UpstreamClient(5_000, client = HttpClient(engine), pacing = RetryPacing(waiter = Waiter { }))

            assertEquals("ok", postOnce(client, login, TurnPerf()))

            assertEquals(listOf("Bearer tok-1"), seenAuth.toList(), "the turn sends the renewal another turn landed")
            assertEquals(listOf("tok-old"), login.spent, "only the old refresh token was ever spent")
        }

    @Test
    fun `a refresh that yields no credential surfaces the 401 without a second upstream call`() = runTest {
        val auth = RotatingAuth(rotated = null)
        val perf = TurnPerf()
        val engine = engineAnswering(HttpStatusCode.Unauthorized)

        val failure = assertEnds<UpstreamFailed> { postOnce(clientOver(engine), auth, perf) }

        assertEquals(401, failure.evidence.status)
        assertEquals(listOf("Bearer tok-old"), seenAuth.toList(), "nothing to reissue with: no second call")
        assertEquals(1, auth.refreshCalls.get())
        assertEquals(1L, perf.snapshot().counters[PerfKeys.ATTEMPTS])
    }
}
