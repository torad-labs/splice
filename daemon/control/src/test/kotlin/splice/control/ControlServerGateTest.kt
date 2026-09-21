// NEW (review gaps K & L, 2026-07-23): control-plane contract tests split out of ControlServerTest
// (which is at its detekt LargeClass ceiling). K: a lowercase `bearer` scheme authenticates on a
// guarded route — the control plane once rejected it until it shared bearerToken. L: /api/heads emits
// the live InflightGate as NUMERIC inflight/queued/max + numeric maxInflight, and unlimited mode as
// max:"unlimited" / maxInflight:null — the webui-shape test only checks gate PRESENCE with zero fakes.
package splice.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import java.net.ServerSocket
import java.nio.file.Files

private class GateFakeHead(
    override val key: String,
    override val port: Int,
    private val inflight: Int,
    private val queued: Int,
    private val limit: Int,
) : Head {
    override val label: String = key
    private var running = true
    override suspend fun start() { running = true }
    override suspend fun stop() { running = false }
    override fun healthSnapshot() = HeadHealth(
        ok = running,
        running = running,
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
    fun `an unauthorized start never reaches the extracted feature`() = runTest {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("control-start-guard").resolve("state"))
        val mgmt = MgmtKey(paths)
        val port = freshPort()
        val server = ControlServer(
            port = port,
            heads = mapOf("codex" to gateHead("codex", 3099, inflight = 0, queued = 0, limit = 0)),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "" },
            log = {},
        )
        server.start()
        try {
            val stopped = client.post("http://127.0.0.1:$port/api/heads/codex/stop") {
                header("Authorization", "Bearer ${mgmt.get()}")
            }
            assertEquals(HttpStatusCode.OK, stopped.status)
            val stoppedHead = json.parseToJsonElement(stopped.bodyAsText()).jsonObject
            assertEquals("false", stoppedHead["running"]?.jsonPrimitive?.content)

            val response = client.post("http://127.0.0.1:$port/api/heads/codex/start")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val heads = client.get("http://127.0.0.1:$port/api/heads") {
                header("Authorization", "Bearer ${mgmt.get()}")
            }
            val head = json.parseToJsonElement(heads.bodyAsText()).jsonObject["heads"]!!.jsonArray.first().jsonObject
            assertEquals("false", head["running"]?.jsonPrimitive?.content)

            val started = client.post("http://127.0.0.1:$port/api/heads/codex/start") {
                header("Authorization", "Bearer ${mgmt.get()}")
            }
            assertEquals(HttpStatusCode.OK, started.status)
            val startedHead = json.parseToJsonElement(started.bodyAsText()).jsonObject
            assertEquals("true", startedHead["running"]?.jsonPrimitive?.content)
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

// No readiness poll: ControlServer.start returns routed and bound (Ktor's default SEQUENTIAL startup
// runs the modules before NettyApplicationEngine's bind(...).sync(); V4-139).
private fun freshPort(): Int = ServerSocket(0).use { it.localPort }
