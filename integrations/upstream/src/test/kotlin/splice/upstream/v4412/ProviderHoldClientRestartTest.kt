package splice.upstream.v4412

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.transport.PostContext
import splice.upstream.transport.UpstreamClient
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.posted
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private const val MS = 1_000L
private const val SIX_DAYS_S = 6L * 24 * 3_600

/** V4-412 through the transport: the daemon is stopped and started over the same state file. */
class ProviderHoldClientRestartTest {
    private val bodyReader = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "body-reader", emptyMap())
        override fun planLimitFromBody(body: String, nowEpochSeconds: Long): PlanLimit? =
            Regex(""""resets_at":(\d+)""").find(body)?.groupValues?.get(1)?.toLong()
                ?.let { PlanLimit("seven_day", it) }
    }

    private fun client(dir: Path, engine: MockEngine) = UpstreamClient(
        totalTimeoutMs = 900_000L,
        maxRetries = 4,
        client = HttpClient(engine),
        backoff = { _, _ -> },
        clock = ElapsedClock { 0L },
        holdStore = FileProviderHoldStore(dir.resolve("hold.json"), LogSink { }),
    )

    private fun ctx(notices: MutableList<String>) = PostContext(
        url = "https://api.example.test/v1/responses",
        auth = bodyReader,
        extraHeaders = { emptyMap() },
        onRetry = { notices.add(it) },
    )

    @Test
    fun `a restarted head still reads out of quota, probes upstream once, and an answer clears the file`(
        @TempDir dir: Path,
    ) = runTest {
        val reset = System.currentTimeMillis() / MS + SIX_DAYS_S
        val refusing = MockEngine {
            respond(
                """{"error":{"type":"usage_limit_reached","resets_at":$reset,"limit_window_minutes":10080}}""",
                HttpStatusCode.TooManyRequests,
                headersOf(),
            )
        }
        assertThrows<UpstreamFailed> { client(dir, refusing).posted(ctx(mutableListOf()), "{}") { "unreachable" } }

        val calls = AtomicInteger()
        val recovered = MockEngine {
            calls.incrementAndGet()
            respond("ok", HttpStatusCode.OK, headersOf())
        }
        val restarted = client(dir, recovered)
        restarted.clearRateLimitCooldown()

        assertEquals(PlanLimit("seven_day", reset), restarted.planHold, "read before any turn")
        assertTrue(restarted.providerResetForMs > (SIX_DAYS_S - 60) * MS, "${restarted.providerResetForMs}")
        val notices = mutableListOf<String>()
        assertEquals("ok", restarted.posted(ctx(notices), "{}") { "ok" })

        assertEquals(1, calls.get(), "the first turn after the restart is the probe, and it went out")
        assertTrue(notices.any { it.startsWith("plan hold: probing upstream") }, notices.toString())
        assertEquals(0L, restarted.providerResetForMs)
        assertEquals(0L, restarted.planHoldForMs)
        assertFalse(Files.exists(dir.resolve("hold.json")), "the answered turn removed the stored statement")
    }
}
