// NEW (review gaps K & L, 2026-07-23): control-plane contract tests split out of ControlServerTest
// (which is at its detekt LargeClass ceiling). K: a lowercase `bearer` scheme authenticates on a
// guarded route — the control plane once rejected it until it shared bearerToken. L: /api/heads emits
// the live InflightGate as NUMERIC inflight/queued/max + numeric maxInflight, and unlimited mode as
// max:"unlimited" / maxInflight:null — the webui-shape test only checks gate PRESENCE with zero fakes.
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.control.CompactView
import splice.control.ControlServer
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadUsageSource
import splice.control.ManagedHead
import splice.control.UsageView
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

private class GateFakeHead(
    override val key: String,
    override val port: Int,
    private val inflight: Int,
    private val queued: Int,
    private val limit: Int,
) : Head {
    override val label: String = key
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun healthSnapshot() = HeadHealth(
        ok = true,
        running = true,
        port = port,
        version = "kt-1",
        gateInflight = inflight,
        gateQueued = queued,
        gateLimit = limit,
    )
}

private class GateFakeAuth : AuthProvider {
    override suspend fun credentials() = null
    override suspend fun describe() = AuthDescription(true, "chatgpt-oauth")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControlServerGateTest {

    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }

    @AfterAll
    fun tearDown() = client.close()

    @Test
    fun `lowercase bearer scheme is accepted on a guarded control route`() = runTest {
        val tmp = Files.createTempDirectory("control-bearer")
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val port = freshPort()
        val server = ControlServer(port, emptyMap(), ConfigService(paths), mgmt, { "" }, {})
        server.start()
        try {
            awaitListening(port)
            val ok = client.get("http://127.0.0.1:$port/api/status") {
                header("Authorization", "bearer ${mgmt.get()}")
            }
            assertEquals(HttpStatusCode.OK, ok.status)
            val wrong = client.get("http://127.0.0.1:$port/api/status") {
                header("Authorization", "bearer wrong-key")
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `api heads emits numeric gate values, and unlimited mode as string-or-null`() = runTest {
        val tmp = Files.createTempDirectory("control-gate")
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val port = freshPort()
        val server = ControlServer(
            port = port,
            heads = mapOf(
                "bounded" to gateHead("bounded", 4101, inflight = 3, queued = 2, limit = 100),
                "unlimited" to gateHead("unlimited", 4102, inflight = 5, queued = 0, limit = 0),
            ),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "" },
            log = {},
        )
        server.start()
        try {
            awaitListening(port)
            val heads = json.parseToJsonElement(
                client.get("http://127.0.0.1:$port/api/heads") {
                    header("Authorization", "Bearer ${mgmt.get()}")
                }.bodyAsText(),
            ).jsonObject["heads"]!!.jsonArray.map { it.jsonObject }

            val bounded = heads.first { it["key"]?.jsonPrimitive?.content == "bounded" }
            val g = bounded["gate"]!!.jsonObject
            assertFalse(g["inflight"]!!.jsonPrimitive.isString, "inflight must be a JSON number")
            assertEquals(3, g["inflight"]!!.jsonPrimitive.int)
            assertEquals(2, g["queued"]!!.jsonPrimitive.int)
            assertFalse(g["max"]!!.jsonPrimitive.isString, "bounded max must be a JSON number")
            assertEquals(100, g["max"]!!.jsonPrimitive.int)
            assertEquals(100, bounded["maxInflight"]!!.jsonPrimitive.int)

            val unlimited = heads.first { it["key"]?.jsonPrimitive?.content == "unlimited" }
            val umax = unlimited["gate"]!!.jsonObject["max"]!!.jsonPrimitive
            assertTrue(umax.isString && umax.content == "unlimited", "unlimited max must be the string")
            assertTrue(unlimited["maxInflight"] is JsonNull, "unlimited maxInflight must be JSON null")
        } finally {
            server.stop()
        }
    }

    private fun gateHead(key: String, port: Int, inflight: Int, queued: Int, limit: Int) = ManagedHead(
        head = GateFakeHead(key, port, inflight, queued, limit),
        auth = GateFakeAuth(),
        usage = HeadUsageSource { UsageView(0L, 0, null) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int) = ""
            override fun path() = "/tmp/$key.log"
        },
        warnPct = 80,
        warnTokens5h = 0,
    )
}

private fun freshPort(): Int = ServerSocket(0).use { it.localPort }

private fun awaitListening(port: Int) {
    val deadline = System.currentTimeMillis() + 10_000
    while (runCatching { Socket("127.0.0.1", port).use { } }.isFailure) {
        check(System.currentTimeMillis() < deadline) { "nothing listening on :$port" }
        Thread.sleep(50)
    }
}
