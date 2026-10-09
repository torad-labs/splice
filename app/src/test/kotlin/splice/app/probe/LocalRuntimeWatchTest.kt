// The daemon keeps each local runtime's reach from a background probe, off the request path: a read of the
// last answer never waits on a probe, a silent runtime is named, and a runtime that returns is dropped.
package splice.app.probe

import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import splice.app.cli.status.LocalRuntimeReach
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LocalRuntimeWatchTest {
    private val logged = mutableListOf<String>()

    private fun local(port: Int) = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "http://127.0.0.1:$port/v1",
        auth = AuthConfig(kind = "api-key", env = "TEST_LOCAL_KEY"),
    )

    private fun head(provider: String, port: Int) = HeadConfig(
        provider = provider,
        port = port,
        discoveryPrefix = "$provider--",
        pinnedModel = "m",
        claude = ClaudeWrapperConfig(command = "claude-$provider"),
    )

    private val topology = Topology(
        providers = mapOf("bonsai-rt" to local(8099), "glml-rt" to local(8101)),
        heads = linkedMapOf("bonsai" to head("bonsai-rt", 3108), "glml53" to head("glml-rt", 3111)),
    )

    private fun watch(http: LocalHttp) = LocalRuntimeWatch(
        topology = topology,
        log = LogSink { logged += it },
        reach = LocalRuntimeReach(http, waitMs = 300L),
    )

    @Test
    fun `nothing is claimed before the first probe has answered`() {
        assertEquals(emptyMap<String, String>(), watch { _, _, _ -> null }.notAnswering())
    }

    @Test
    fun `a probe's answer is held, naming the silent runtime and not the one that answers`() {
        val watch = watch { _, url, _ -> LocalHttpReply(200, "{}").takeIf { url.contains(":8101") } }
        watch.tick()
        assertEquals(mapOf("bonsai" to ":8099"), watch.notAnswering())
    }

    @Test
    fun `a runtime coming back is dropped at the next probe, and both changes are logged once`() {
        val up = AtomicBoolean(false)
        val watch = watch { _, _, _ -> LocalHttpReply(200, "{}").takeIf { up.get() } }
        watch.tick()
        watch.tick()
        assertEquals(setOf("bonsai", "glml53"), watch.notAnswering().keys)
        up.set(true)
        watch.tick()
        assertEquals(emptyMap<String, String>(), watch.notAnswering())
        assertEquals(1, logged.count { it.contains("runtime not answering on :8099") }, logged.toString())
        assertEquals(1, logged.count { it.contains("bonsai") && it.contains("answering again") }, logged.toString())
    }

    @Test
    fun `a read never waits on a probe that is still running`() {
        val wedged = CountDownLatch(1)
        val watch = watch { _, _, _ ->
            wedged.await(WEDGE_S, TimeUnit.SECONDS)
            null
        }
        val probing = thread { watch.tick() }
        try {
            val bound = Duration.ofMillis(READ_BOUND_MS)
            val read = assertTimeoutPreemptively(bound, ThrowingSupplier { watch.notAnswering() })
            assertEquals(emptyMap<String, String>(), read, "the probe has not landed, so nothing is claimed yet")
        } finally {
            wedged.countDown()
            probing.join()
        }
    }

    @Test
    fun `a watch that dies stops claiming what it can no longer measure, and says so`() {
        val watch = watch { _, _, _ -> null }
        watch.tick()
        assertEquals(setOf("bonsai", "glml53"), watch.notAnswering().keys)
        watch.supervise(CompletableDeferred<Unit>().also { it.completeExceptionally(IllegalStateException("boom")) })
        assertEquals(emptyMap<String, String>(), watch.notAnswering())
        assertTrue(logged.any { it.contains("DIED") }, logged.toString())
    }
}

private const val READ_BOUND_MS = 250L
private const val WEDGE_S = 5L
