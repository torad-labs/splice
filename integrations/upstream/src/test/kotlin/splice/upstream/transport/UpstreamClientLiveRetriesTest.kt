// upstreamRetries is live: a request asks for its bound as it begins, so a PATCH governs the next request and not
// only the next restart, and a request already running keeps the bound it started with.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientLiveRetriesTest {

    @Test
    fun `a bound raised after the client was built is the one the next request is given`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("overloaded", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        var tries = 2
        val client = UpstreamClient(
            totalTimeoutMs = 5_000,
            maxRetries = 4,
            client = HttpClient(engine),
            pacing = RetryPacing(backoff = { _, _ -> }, liveRetries = LiveRetries { tries }),
        )

        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(2, calls.get(), "the bound it was given, not the 4 the client was built with")

        tries = 5
        calls.set(0)
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(5, calls.get(), "the raised bound governs the next request")
    }
}
