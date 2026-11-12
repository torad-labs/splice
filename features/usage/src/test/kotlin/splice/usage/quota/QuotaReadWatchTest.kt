package splice.usage.quota

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.usage.QuotaReadFailure
import splice.core.usage.QuotaReadOutcome
import splice.core.usage.QuotaReadReason
import splice.core.util.Cancellables
import splice.core.util.WallClock
import java.io.IOException

/** Oct 12, 2026: Accounts says why a login's limits are "Not read". Each real probe here answers or fails as a
 *  provider would, and the reason the console words is the one the read ends with; an answer clears it. */
class QuotaReadWatchTest {
    private val nowMs = 1_788_000_000_000L
    private val kimiBody = """{"usage":{"limit":"100","remaining":"74","resetTime":"2026-09-05T04:17:14Z"}}"""

    @Test
    fun `each way a limits read fails ends in its reason, with its time`() = runTest {
        val cases = listOf(
            read(answering(HttpStatusCode.Forbidden)) to QuotaReadReason.SIGNED_OUT,
            read(answering(HttpStatusCode.Unauthorized)) to QuotaReadReason.SIGNED_OUT,
            read(answering(HttpStatusCode.TooManyRequests)) to QuotaReadReason.REFUSED,
            read(answering(HttpStatusCode.OK, """{"error":{"type":"x"}}""")) to QuotaReadReason.REFUSED,
            read(MockEngine { throw IOException("connect timed out") }) to QuotaReadReason.NOT_ANSWERING,
            read(answering(HttpStatusCode.OK, kimiBody), creds = null) to QuotaReadReason.SIGNED_OUT,
            read(answering(HttpStatusCode.OK, kimiBody), Credentials.ClientForwarded, kind = "client") to
                QuotaReadReason.UNSUPPORTED,
        )
        cases.forEach { (failure, reason) ->
            assertEquals(QuotaReadFailure(reason, nowMs / 1_000), failure, "$reason")
        }
    }

    @Test
    fun `a read that answers clears the reason the last one left`() = runTest {
        var last: QuotaReadFailure? = QuotaReadFailure(QuotaReadReason.REFUSED, 1L)
        val probe = QuotaProbes(HttpClient(answering(HttpStatusCode.OK, kimiBody)), WallClock { nowMs })
            .forHead("kimi-oauth", BASE, Fixed(Credentials.Bearer("tok")), null)!!

        val snapshot = QuotaReadWatch(probe, QuotaReadOutcome { last = it }, WallClock { nowMs }).probe()

        assertTrue(snapshot != null, "the reading itself still reaches the poller")
        assertNull(last)
    }

    /** The failure one read through [engine] leaves, rethrown to the poller as before. */
    private suspend fun read(
        engine: MockEngine,
        creds: Credentials? = Credentials.Bearer("tok"),
        kind: String = "kimi-oauth",
    ): QuotaReadFailure? {
        var left: QuotaReadFailure? = null
        val probe = QuotaProbes(HttpClient(engine), WallClock { nowMs })
            .forHead(kind, BASE, Fixed(creds), null, ClientUserAgent { null })!!
        val thrown = Cancellables.runCatchingCancellable {
            QuotaReadWatch(probe, QuotaReadOutcome { left = it }, WallClock { nowMs }).probe()
        }.exceptionOrNull()
        assertTrue(thrown != null, "the poller still sees the failure")
        return left
    }

    private fun answering(status: HttpStatusCode, body: String = "{}") = MockEngine { respond(body, status) }

    private class Fixed(private val creds: Credentials?) : AuthProvider {
        override suspend fun credentials(): Credentials? = creds
        override suspend fun describe(): AuthDescription = AuthDescription(creds != null, "test")
    }
}

private const val BASE = "https://api.example.test/coding"
