// NEW (V4-73): a provider-declared QUOTA EXHAUSTION becomes a 429 at the one place that builds
// RetryOutcome.Failed, so every layer above — rateLimitedPlan arming the shared cooldown, the pool's
// markUnavailable, the thrown UpstreamFailed status, the classifier's RATE_LIMIT and the admission
// 429 with its client-retry headers — treats credits exhaustion as the rate limit it is, without any
// of them learning a vendor's spelling. Before this, grok's 403 spending-limit wall reached the
// client as terminal invalid_request_error and the operator's own words were that credits exhaustion
// is retried until the credits are back.
//
// Three arms, and the CONTROL matters as much as the two rewrites: an undeclared 403 must stay a
// 403, or the fix would quietly reclassify genuine permission failures as rate limits.
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
import splice.core.wire.HttpStatus
import splice.spi.ClientFrameEmitted
import splice.spi.PostContext
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed
import java.util.concurrent.atomic.AtomicInteger

/** A billing 403 body the way grok spells it, and the statuses the arms exercise. */
private const val SPENDING_LIMIT_BODY =
    """{"error":{"code":"personal-team-blocked","message":"spending-limit: you have run out of credits"}}"""

class QuotaExhaustionIs429Test {

    /** The auth port ANSWERING the neutral question — the vendor spelling lives in the provider,
     *  so this stub is exactly what the transport sees: a boolean and no phrases. */
    private class QuotaAuth(
        private val declared: Boolean,
        private val refreshes: AtomicInteger,
    ) : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer("tok-quota", "acct")
        override suspend fun refresh(): Credentials {
            refreshes.incrementAndGet()
            return credentials()
        }

        override suspend fun describe(): AuthDescription = AuthDescription(true, "quota-stub")

        override fun isQuotaExhausted(status: Int, body: String): Boolean = declared && status == 403
    }

    private fun ctx(auth: RefreshableAuthProvider, notices: MutableList<String>) = PostContext(
        url = "https://api.example.test/v1",
        auth = auth,
        extraHeaders = { emptyMap() },
        onRetry = { notices.add(it) },
        clientFrameEmitted = ClientFrameEmitted { true },
    )

    private fun clientOver(engine: MockEngine) = UpstreamClient(
        firstByteTimeoutMs = 5_000,
        totalTimeoutMs = 5_000,
        maxRetries = 1,
        client = HttpClient(engine),
        backoff = { _, _ -> },
        dnsBackoff = { _ -> },
    )

    private fun engineReturning(status: Int) = MockEngine {
        respond(SPENDING_LIMIT_BODY, HttpStatusCode.fromValue(status), headersOf())
    }

    @Test
    fun `a declared quota-exhaustion 403 is thrown as a 429 and arms the cooldown`() = runTest {
        val refreshes = AtomicInteger()
        val notices = mutableListOf<String>()
        val client = clientOver(engineReturning(403))
        val thrown = assertThrows<UpstreamFailed> {
            client.posted(ctx(QuotaAuth(declared = true, refreshes), notices), "{}") { "unreachable" }
        }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, thrown.status, "the rewrite is what every layer above reads")
        assertTrue(client.rateLimitedForMs > 0L, "a quota wall must arm the follower horizon like a 429")
        assertEquals(0, refreshes.get(), "billing is not auth: no refresh may be spent on a quota wall")
        assertTrue(
            notices.any { it.contains("403 is a quota exhaustion") && it.contains("${HttpStatus.TOO_MANY_REQUESTS}") },
            "one line must name the REAL status and the rewrite, got: $notices",
        )
    }

    @Test
    fun `a 402 is a quota exhaustion for every provider, declared or not`() = runTest {
        val refreshes = AtomicInteger()
        val notices = mutableListOf<String>()
        // NOT declared by the auth port: 402 is a protocol fact, not a vendor spelling.
        val client = clientOver(engineReturning(402))
        val thrown = assertThrows<UpstreamFailed> {
            client.posted(ctx(QuotaAuth(declared = false, refreshes), notices), "{}") { "unreachable" }
        }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, thrown.status, "deepseek answers Insufficient Balance as 402")
        assertTrue(client.rateLimitedForMs > 0L, "and it arms the horizon the same way")
        assertEquals(0, refreshes.get())
        assertTrue(
            notices.any { it.contains("402 is a quota exhaustion") },
            "the notice must name the real status, got: $notices",
        )
    }

    @Test
    fun `a 403 nobody declares stays a 403 - the control`() = runTest {
        val refreshes = AtomicInteger()
        val notices = mutableListOf<String>()
        val client = clientOver(engineReturning(403))
        val thrown = assertThrows<UpstreamFailed> {
            client.posted(ctx(QuotaAuth(declared = false, refreshes), notices), "{}") { "unreachable" }
        }

        assertEquals(403, thrown.status, "an undeclared 403 keeps today's behaviour exactly")
        assertEquals(0L, client.rateLimitedForMs, "and must NOT arm the horizon")
        assertEquals(0, refreshes.get())
        assertTrue(notices.none { it.contains("quota exhaustion") }, "no rewrite notice for an ordinary 403")
    }
}
