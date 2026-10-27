// NEW: the shared test fixture for the UpstreamClient retry-policy suite, split out of
// UpstreamClientRetryPolicyTest.kt by V4-63. Pure move: byte-identical helpers, visibility changed
// from class-private to internal so more than one sibling class can use them. Nothing asserts here.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.upstream.Waiter
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.Replies

internal val fakeAuth = object : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
    override suspend fun refresh(): Credentials? = null
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake", emptyMap())
}

internal class Capture {
    /** Every wait the turn's retry steps asked for, in order (failure-handling slice 4: RetryEngine names each one). */
    val waits = mutableListOf<Long>()
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

/** A client that takes RetryEngine's steps as shipped: only the wait is faked, and recorded. */

internal fun clientOver(
    engine: MockEngine,
    capture: Capture = Capture(),
    clock: () -> Long = System::currentTimeMillis,
) = UpstreamClient(
    totalTimeoutMs = 5_000,
    client = HttpClient(engine),
    pacing = RetryPacing(waiter = Waiter { ms -> capture.waits.add(ms) }),
    clock = clock,
)

internal suspend fun postOnce(client: UpstreamClient): String = client.posted(
    PostContext(
        url = "https://api.example.test/v1",
        auth = fakeAuth,
        extraHeaders = { emptyMap() },
        replies = Replies(OpenAiReplies),
    ),
    "{}",
) { "ok" }
