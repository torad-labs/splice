// NEW: the shared test fixture for the UpstreamClient retry-policy suite, split out of
// UpstreamClientRetryPolicyTest.kt by V4-63. Pure move: byte-identical helpers, visibility changed
// from class-private to internal so more than one sibling class can use them. Nothing asserts here.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.spi.PostContext
import splice.spi.UpstreamClient
import splice.spi.Waiter

internal val fakeAuth = object : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
    override suspend fun refresh(): Credentials? = null
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake", emptyMap())
}

internal class Capture {
    val minDelays = mutableListOf<Long>()
}

/** HD-19: the Waiter seam as a recorder. It captures what the PRODUCTION backoff lambda asked
 *  to wait and returns instantly, which is what lets the tests below run the real curve instead
 *  of replacing it with `{ _, _ -> }` and re-deriving its arithmetic in the assertion. */

internal class RecordingWaiter : Waiter {
    val waits = mutableListOf<Long>()

    override suspend fun wait(ms: Long) {
        waits.add(ms)
    }
}

/** A client whose backoff lambdas are the SHIPPED defaults — only the wait is faked. */

internal fun clientOver(
    engine: MockEngine,
    capture: Capture = Capture(),
    clock: () -> Long = System::currentTimeMillis,
) = UpstreamClient(
    firstByteTimeoutMs = 5_000,
    totalTimeoutMs = 5_000,
    maxRetries = 3,
    client = HttpClient(engine),
    backoff = { _, minDelayMs -> capture.minDelays.add(minDelayMs) },
    clock = clock,
)

internal suspend fun postOnce(client: UpstreamClient): String = client.posted(
    PostContext(url = "https://api.example.test/v1", auth = fakeAuth, extraHeaders = { emptyMap() }),
    "{}",
) { "ok" }
