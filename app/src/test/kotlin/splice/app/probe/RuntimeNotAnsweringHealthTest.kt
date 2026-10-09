// /health and the heads route carry each local runtime's reach from the daemon's held snapshot: a silent
// runtime is named, one that answers is unmarked, and ok / readyHeads / failedHeads stay what launch shims wait on.
package splice.app.probe

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import splice.app.control.ManagedHead
import splice.app.control.RuntimeNotAnswering
import splice.app.control.api.HeadResolver
import splice.app.control.api.HeadSignals
import splice.app.control.healthFor
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration

private const val FIELD = "runtimeNotAnswering"

private class UpHead(override val key: String) : Head {
    override val label = key
    override val port = 0
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun healthSnapshot() = HeadHealth(ok = true, running = true, port = 0, version = "kt-1")
}

class RuntimeNotAnsweringHealthTest {
    private fun managed(key: String) = ManagedHead(
        head = UpHead(key),
        auth = object : splice.core.auth.AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = splice.core.auth.AuthDescription(false, "x", emptyMap())
        },
        usage = object : HeadUsageSource {
            override fun snapshot() = UsageView(0L, 0, RateLimitView(0, 0, "0s"))
        },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int) = ""
            override fun path() = "/tmp/x.log"
        },
        warnPct = 80,
        warnTokens5h = 0,
        authKind = "x",
    )

    /** The one signals value a test reads both surfaces through, so the health body and the heads route
     *  are given the same silent runtimes. */
    private fun signals(heads: Map<String, ManagedHead>, silent: RuntimeNotAnswering?) =
        HeadSignals(heads, silent ?: RuntimeNotAnswering { emptyMap() })

    private fun health(heads: Map<String, ManagedHead>, silent: RuntimeNotAnswering?): JsonObject =
        Json.parseToJsonElement(healthFor(heads, signals = signals(heads, silent)).json()).jsonObject

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

    private val heads = linkedMapOf(
        "bonsai" to managed("bonsai"),
        "glml53" to managed("glml53"),
        "claude" to managed("claude"),
    )

    @Test
    fun `health names the head whose runtime is silent, and its ready count is what it was`() {
        val body = health(heads, RuntimeNotAnswering { mapOf("bonsai" to ":8099") })
        assertEquals(":8099", body.getValue(FIELD).jsonObject.getValue("bonsai").jsonPrimitive.content)
        assertEquals(setOf("bonsai"), body.getValue(FIELD).jsonObject.keys)
        assertTrue(body.getValue("ok").jsonPrimitive.boolean, "a silent runtime is not a broken daemon")
        assertEquals(3, body.getValue("readyHeads").jsonPrimitive.int)
        assertEquals(0, body.getValue("failedHeads").jsonPrimitive.int)
    }

    @Test
    fun `health without a silent runtime has no such field, so its shape is what it was`() {
        assertFalse(health(heads, null).containsKey(FIELD))
        assertFalse(health(heads, RuntimeNotAnswering { emptyMap() }).containsKey(FIELD))
    }

    @Test
    fun `the heads route marks the silent runtime's head and no other`() {
        val silent = signals(heads, RuntimeNotAnswering { mapOf("bonsai" to ":8099") })
        val statuses = HeadResolver(heads, silent).headStatuses()
            .associateBy { it.getValue("key").jsonPrimitive.content }
        assertEquals(":8099", statuses.getValue("bonsai").getValue(FIELD).jsonPrimitive.content)
        assertNull(statuses.getValue("glml53")[FIELD])
        assertNull(statuses.getValue("claude")[FIELD])
        assertTrue(statuses.getValue("bonsai").getValue("running").jsonPrimitive.boolean, "the head itself still runs")
    }

    @Test
    fun `a refused runtime and one that answers, probed for real, read back through health and the heads route`() {
        val refused = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val answering = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        try {
            val topology = Topology(
                providers = mapOf("rt-down" to local(refused), "rt-up" to local(answering.address.port)),
                heads = linkedMapOf("bonsai" to head("rt-down", 3108), "glml53" to head("rt-up", 3111)),
            )
            val watch = LocalRuntimeWatch(topology, LogSink {})
            watch.tick()
            val silent = RuntimeNotAnswering(watch::notAnswering)
            val bound = Duration.ofMillis(READ_BOUND_MS)
            val body = assertTimeoutPreemptively(bound, ThrowingSupplier { health(heads, silent) })
            assertEquals(setOf("bonsai"), body.getValue(FIELD).jsonObject.keys)
            assertEquals(":$refused", body.getValue(FIELD).jsonObject.getValue("bonsai").jsonPrimitive.content)
            val marks = HeadResolver(heads, signals(heads, silent)).headStatuses()
                .filter { it.containsKey(FIELD) }.map { it.getValue("key").jsonPrimitive.content }
            assertEquals(listOf("bonsai"), marks)
        } finally {
            answering.stop(0)
        }
    }
}

private const val READ_BOUND_MS = 500L
