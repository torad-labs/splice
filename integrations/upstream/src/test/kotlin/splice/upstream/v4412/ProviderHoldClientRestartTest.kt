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
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.core.wire.RateLimitReply
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.ProviderHold
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.PostContext
import splice.upstream.transport.RetryPacing
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

    private fun client(dir: Path, engine: MockEngine, clock: ElapsedClock = ElapsedClock { 0L }) = UpstreamClient(
        totalTimeoutMs = 900_000L,
        maxRetries = 4,
        client = HttpClient(engine),
        pacing = RetryPacing(backoff = { _, _ -> }),
        clock = clock,
        holdStore = FileProviderHoldStore(dir.resolve("hold.json"), LogSink { }),
    )

    private fun ctx(notices: MutableList<String>) = PostContext(
        url = "https://api.example.test/v1/responses",
        auth = bodyReader,
        extraHeaders = { emptyMap() },
        onRetry = { notices.add(it) },
    )

    @Test
    fun `a restarted native hold refuses only its credential and reprobes after the bounded ceiling`(
        @TempDir dir: Path,
    ) = runTest {
        val reset = System.currentTimeMillis() / MS + SIX_DAYS_S
        val native = """{"error":{"type":"rate_limit_error","resets_at":$reset,"message":"synthetic native refusal"}}"""
        fun context(key: String) = PostContext(
            url = "https://api.example.test/v1/messages",
            auth = object : RefreshableAuthProvider by bodyReader {
                override suspend fun credentials(): Credentials = Credentials.ApiKey(key, "x-api-key", "")
            },
            extraHeaders = { emptyMap() },
        ).also { it.relayRateLimitReplies = true }
        val refusing = MockEngine {
            respond(native, HttpStatusCode.TooManyRequests, headersOf("x-should-retry", listOf("true", "false")))
        }
        val original = client(dir, refusing)
        val observer = assertThrows<UpstreamFailed> { original.posted(context("synthetic-a"), "{}") { "unreachable" } }
        assertEquals(1, refusing.requestHistory.size, "a native refusal is never retried by splice")
        val key = requireNotNull(CredentialKey.fromHeaders(mapOf("x-api-key" to "synthetic-a")))
        assertTrue(Files.exists(dir.resolve("hold-$key.json")))

        var elapsed = 0L
        val attempts = mutableListOf<String>()
        val recovered = MockEngine { request ->
            attempts += requireNotNull(request.headers["x-api-key"])
            respond("ok", HttpStatusCode.OK, headersOf())
        }
        val restarted = client(dir, recovered, ElapsedClock { elapsed })
        restarted.clearRateLimitCooldown()

        val follower = assertThrows<UpstreamFailed> {
            restarted.posted(context("synthetic-a"), "{}") { "unreachable" }
        }
        assertTrue(follower.localHold)
        assertEquals(429, follower.status)
        assertEquals(native, follower.body)
        assertEquals(observer.rateLimitReply, follower.rateLimitReply)
        assertTrue(attempts.isEmpty(), "the restored credential's refusal makes zero upstream attempts")
        assertEquals("ok", restarted.posted(context("synthetic-b"), "{}") { "ok" })
        assertEquals(listOf("synthetic-b"), attempts, "another login on the same head is unaffected")

        elapsed = 119_999L
        assertThrows<UpstreamFailed> { restarted.posted(context("synthetic-a"), "{}") { "unreachable" } }
        assertEquals(listOf("synthetic-b"), attempts)
        elapsed = 120_001L
        assertEquals("ok", restarted.posted(context("synthetic-a"), "{}") { "ok" })
        assertEquals(listOf("synthetic-b", "synthetic-a"), attempts, "the restored gate cannot extend on reads")
        assertFalse(Files.exists(dir.resolve("hold-$key.json")), "provider acceptance clears the native statement")
    }

    @Test
    fun `a stored native hold ends at a nearer reset and an expired reply never arms`(@TempDir dir: Path) {
        val store = FileProviderHoldStore(dir.resolve("hold.json"), LogSink {})
        store.save(
            ProviderHold(101L, null).also {
                it.rateLimitReply = RateLimitReply("synthetic refusal", emptyMap())
            },
        )
        var elapsed = 0L
        var wall = 100_000L
        val cooldown = RateLimitCooldown(ElapsedClock { elapsed }, WallClock { wall }, store)
        cooldown.clear()
        assertEquals(1_000L, cooldown.remainingMs())
        assertThrows<UpstreamFailed> { cooldown.failFastIfArmed {} }
        elapsed = 1_001L
        wall = 101_001L
        assertEquals(0L, cooldown.remainingMs())
        cooldown.failFastIfArmed {}
        val expired = RateLimitCooldown(ElapsedClock { elapsed }, WallClock { wall }, store)
        expired.clear()
        assertEquals(0L, expired.remainingMs())
        assertEquals(null, expired.rateLimitReply)
        assertFalse(Files.exists(dir.resolve("hold.json")))
    }

    @Test
    fun `a legacy unscoped file never claims ownership of a newly proved credential`(@TempDir dir: Path) = runTest {
        val reset = System.currentTimeMillis() / MS + SIX_DAYS_S
        val path = dir.resolve("hold.json")
        FileProviderHoldStore(path, LogSink {}).save(ProviderHold(reset, PlanLimit("seven_day", reset)))
        val engine = MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) }
        val current = client(dir, engine)
        assertEquals(null, current.planHold)
        assertEquals(0L, current.providerResetForMs)
        assertEquals("ok", current.posted(ctx(mutableListOf()), "{}") { "ok" })
        assertTrue(Files.exists(path), "unknown ownership is not a license to delete the old statement")
    }

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
        val key = requireNotNull(CredentialKey.fromHeaders(mapOf("x-api-key" to "k")))
        assertFalse(Files.exists(dir.resolve("hold-$key.json")), "the answered credential removed its stored statement")
    }
}
