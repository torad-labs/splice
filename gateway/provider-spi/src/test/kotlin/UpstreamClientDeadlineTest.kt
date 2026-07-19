// NEW (G4d): cross-attempt wall-clock deadline pins. The retry loop reuses totalTimeoutMs as the
// route-timeout analog (firstByteTimeoutMs already covers the per-try budget via HttpTimeout) — a
// deadline check runs before every new attempt AND before every backoff sleep, on both the
// HTTP-status BACKOFF path and the transport-error retry path, so a pathological run of
// repeated-slow-failing-attempts cannot spin past the budget even with maxRetries left on the
// clock. MockEngine — no network; a fake stepping clock stands in for real sleeps.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientDeadlineTest {

    private val fakeAuth = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "fake", emptyMap())
    }

    private fun clientOver(
        engine: MockEngine,
        totalTimeoutMs: Long,
        maxRetries: Int,
        clock: () -> Long,
    ) = UpstreamClient(
        firstByteTimeoutMs = 5_000,
        totalTimeoutMs = totalTimeoutMs,
        maxRetries = maxRetries,
        client = HttpClient(engine),
        backoff = { _, _ -> },
        clock = clock,
    )

    private suspend fun postOnce(client: UpstreamClient): String = client.post(
        url = "https://api.example.test/v1",
        bodyJson = "{}",
        auth = fakeAuth,
        extraHeaders = { emptyMap() },
    ) { "ok" }

    @Test
    fun `deadline exceeded gives up before exhausting maxRetries on repeated 5xx failures`() = runTest {
        val calls = AtomicInteger()
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            now += 3_000
            respond("boom", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val client = clientOver(engine, totalTimeoutMs = 5_000, maxRetries = 10) { now }
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertTrue(calls.get() < 10, "deadline should cut the loop short, not exhaust attempts (${calls.get()})")
    }

    @Test
    fun `deadline exceeded gives up before exhausting maxRetries on repeated transport failures`() = runTest {
        val calls = AtomicInteger()
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            now += 3_000
            throw ConnectException("refused")
        }
        val client = clientOver(engine, totalTimeoutMs = 5_000, maxRetries = 10) { now }
        assertThrows<ConnectException> { postOnce(client) }
        assertTrue(calls.get() < 10, "deadline should cut the loop short, not exhaust attempts (${calls.get()})")
    }

    @Test
    fun `ample deadline still allows the full attempt budget`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond("boom", HttpStatusCode.ServiceUnavailable, headersOf())
            } else {
                respond("fine", HttpStatusCode.OK, headersOf())
            }
        }
        val client = clientOver(engine, totalTimeoutMs = 60_000, maxRetries = 3, clock = System::currentTimeMillis)
        assertEquals("ok", postOnce(client))
        assertEquals(2, calls.get())
    }

    @Test
    fun `deadline check fires before the very first attempt if already exceeded at entry`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("fine", HttpStatusCode.OK, headersOf())
        }
        val client = clientOver(engine, totalTimeoutMs = 0, maxRetries = 3) { 0L }
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(0, calls.get())
    }
}
