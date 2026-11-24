// Server mode (docs/specs/server-mode.md, F4): the daemon's pools and their health checks, as the upstream list
// reads them. Each server is asked `/health` when its pool is made and again every `health_seconds`; the test stands
// in for the servers and for the clock between checks, so each reading follows from an answer it chose.
package splice.app.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.UpstreamConfig
import splice.core.util.LogSink
import splice.dialect.chat.ChatReadinessProbe
import splice.upstream.Ticker
import splice.upstream.transport.LocalHttpReply
import java.io.IOException
import java.io.UncheckedIOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpstreamPoolsTest {
    /** What each server's `/health` answers now, by its address: a status, or nothing for one that does not answer. */
    private val answers = mutableMapOf<String, Int?>()
    private val asked = mutableListOf<String>()
    private val carried = mutableListOf<Map<String, String>>()
    private val waited = mutableListOf<Long>()
    private val nextCheck = Channel<Boolean>(Channel.UNLIMITED)

    private val box = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        auth = AuthConfig(kind = "api-key", env = "BOX_KEY"),
        upstreams = listOf(
            UpstreamConfig("gpu-1", "http://10.0.0.21:8080/v1"),
            UpstreamConfig("gpu-2", "http://10.0.0.22:8080/v1"),
        ),
        healthSeconds = 2,
    )

    /** A completion probe's question and login, which a health check never asks. */
    private val ready = PoolReadiness(
        ChatReadinessProbe("max_tokens"),
        object : AuthProvider {
            override suspend fun credentials(): Credentials? = null

            override suspend fun describe(): AuthDescription = AuthDescription(false, "api-key")
        },
        emptyMap(),
    )

    /** The pools, checking on the test's scheduler: a check runs the moment the test lets the clock tick. */
    private fun TestScope.pools(): UpstreamPools = UpstreamPools(
        scope = backgroundScope,
        log = LogSink { },
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        ticker = Ticker { everyMs ->
            waited += everyMs
            nextCheck.receive()
        },
        http = { headers, _ ->
            carried += headers
            { _, url, _ ->
                asked += url
                answers[url]?.let { LocalHttpReply(it, "{}") }
            }
        },
    )

    /** Lets every server's next health check run. */
    private suspend fun tick() = repeat(box.upstreams.size) { nextCheck.send(true) }

    @Test
    fun `each server is asked its health when the pool is made and every health_seconds after, and reads as it said`() =
        runTest {
            answers["http://10.0.0.21:8080/health"] = 200
            answers["http://10.0.0.22:8080/health"] = 503
            val pool = checkNotNull(pools().of("box", box, ready) { emptyMap() })
            assertEquals(listOf("http://10.0.0.21:8080/health", "http://10.0.0.22:8080/health"), asked)
            assertEquals(listOf(true, false), pool.readings().map { it.healthy })
            assertEquals(listOf(2_000L, 2_000L), waited)

            answers["http://10.0.0.21:8080/health"] = null
            answers["http://10.0.0.22:8080/health"] = 204
            tick()
            assertEquals(listOf(false, true), pool.readings().map { it.healthy }, "silence is down, any 2xx is up")
        }

    @Test
    fun `a check carries what a turn carries, as it stands when the check is made`() = runTest {
        var key = "first"
        pools().of("box", box, ready) { mapOf("Authorization" to "Bearer $key") }
        key = "rotated"
        tick()
        val sent = carried.map { it["Authorization"] }
        assertEquals(listOf("Bearer first", "Bearer first", "Bearer rotated", "Bearer rotated"), sent)
    }

    @Test
    fun `a check that could not be made reads the server down, and the next check brings it back`() = runTest {
        answers["http://10.0.0.21:8080/health"] = 200
        answers["http://10.0.0.22:8080/health"] = 200
        var broken = true
        val headers = ServerHeaders {
            if (broken) throw UncheckedIOException(IOException("no key file"))
            emptyMap()
        }
        val pool = checkNotNull(pools().of("box", box, ready, headers))
        assertEquals(listOf(false, false), pool.readings().map { it.healthy })
        broken = false
        tick()
        assertEquals(listOf(true, true), pool.readings().map { it.healthy })
    }

    @Test
    fun `a turn one head of a provider has in flight is in flight for its other head, and each server has one row`() =
        runTest {
            answers["http://10.0.0.21:8080/health"] = 200
            answers["http://10.0.0.22:8080/health"] = 200
            val pools = pools()
            val first = checkNotNull(pools.of("box", box, ready) { emptyMap() })
            val second = checkNotNull(pools.of("box", box, ready) { emptyMap() })

            val turn = checkNotNull(first.lease())
            assertEquals(listOf(1, 0), second.readings().map { it.inflight })
            assertEquals(listOf("gpu-1" to 1, "gpu-2" to 0), rows(pools), "the upstream list has a row for each server")
            turn.release()
            assertEquals(listOf("gpu-1" to 0, "gpu-2" to 0), rows(pools))
        }

    @Test
    fun `a pool made after server mode named its per-upstream cap holds it from its first turn`() = runTest {
        answers["http://10.0.0.21:8080/health"] = 200
        answers["http://10.0.0.22:8080/health"] = 200
        val pools = pools()
        pools.capEach(1)
        val pool = checkNotNull(pools.of("box", box) { emptyMap() })

        val held = listOf(checkNotNull(pool.lease()), checkNotNull(pool.lease()))
        assertNull(pool.lease(), "a turn was placed past inflight_per_upstream on a pool no turn had reserved in")
        assertEquals(listOf(1, 1), pool.readings().map { it.inflight })
        held.forEach { it.release() }
    }

    @Test
    fun `a provider that is one server has no pool, and no row in the upstream list`() = runTest {
        val pools = pools()
        val one = box.copy(baseUrl = "http://10.0.0.9:8080/v1", upstreams = emptyList())
        assertNull(pools.of("solo", one, ready) { emptyMap() })
        assertEquals(emptyList<Pair<String, Int>>(), rows(pools))
    }

    /** The upstream list as it reads now: each row's server and the turns in flight on it. */
    private fun rows(pools: UpstreamPools): List<Pair<String, Int>> =
        pools.all().flatMap { it.readings() }.map { it.name to it.inflight }
}
