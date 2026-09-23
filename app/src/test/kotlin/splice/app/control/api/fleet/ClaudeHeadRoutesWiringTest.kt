// NEW: V4-129 — GET/POST /api/claude-head{,/wrap,/unwrap} through a REAL ControlServer.
//
// This rig proves registration and the bearer guard (the established convention: a lost registration
// line or a route outside guarded() fails HERE, not in production). It never drives a wrap/unwrap PAST
// the 401 the guard answers without a bearer, because ClaudeHeadRoutes' default `home` is the REAL
// machine's home and an authorized wrap on it would write into the operator's actual ~/.claude. The
// full wrap/unwrap behavior is the launch feature's hermetic ClaudeHeadRoutesTest (LAYOUT-01 split).
package splice.app.control.api.fleet

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.control.ControlServer
import splice.app.control.HeadLogSource
import splice.app.control.ManagedHead
import splice.client.ClaudePolicy
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.launch.HeadTrees
import splice.launch.LaunchSpec
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L

class ClaudeHeadRoutesWiringTest {

    private fun launchSpec(configDir: Path) = LaunchSpec(
        trees = HeadTrees(configDir),
        pinnedModel = "claude-fable-5",
        availableModelIds = listOf("claude-fable-5"),
        modelLabels = mapOf("claude-fable-5" to "Claude Fable 5"),
        contextWindow = 200_000,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "\"/bin/curl\" -s :3096/statusline/claude-splice",
        loginCommand = "claude-splice login",
        signInLabel = "Claude",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3104,
        inferenceToken = "test-token",
        apiTimeoutMs = 960_000,
        forwardClientAuth = true,
        headKey = "claude-splice",
    )

    private fun managedHead(configDir: Path): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = "claude-splice"
            override val label: String = "claude-splice"
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "client", emptyMap())
        },
        usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
        launchSpec = launchSpec(configDir),
    )

    // ── wired: through a REAL ControlServer — proves registration + the bearer guard ─────────────

    private fun serveWired(test: suspend (port: Int, key: String) -> Unit) {
        val tmp = Files.createTempDirectory("claude-head-routes-wired")
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val key = mgmt.get()
        val control = ControlServer(
            port = 0, // bound by the OS at start and read back below: no lease-then-bind window
            heads = mapOf("claude-splice" to managedHead(tmp.resolve(".claude-claude-splice"))),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
        )
        runBlocking { control.start() }
        val port = control.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
                    while (System.nanoTime() < deadline && runCatching { ServerSocket(port).close() }.isSuccess) {
                        delay(POLL_MS)
                    }
                    test(port, key)
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }

    @Test
    fun `every claude-head route is registered and needs the bearer`() {
        serveWired { port, _ ->
            val client = HttpClient(CIO) { expectSuccess = false }
            try {
                val url = "http://127.0.0.1:$port"
                assertEquals(HttpStatusCode.Unauthorized, client.get("$url/api/claude-head").status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("$url/api/claude-head/wrap").status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("$url/api/claude-head/unwrap").status)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `an authorized GET answers 200 with a mode field`() {
        serveWired { port, key ->
            val client = HttpClient(CIO) { expectSuccess = false }
            try {
                val response = client.get("http://127.0.0.1:$port/api/claude-head") {
                    header("Authorization", "Bearer $key")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertTrue(body["mode"]!!.jsonPrimitive.content in setOf("separate", "wrapped"))
                assertTrue(body.containsKey("shim_path"))
                assertTrue(body.containsKey("claude_logins"))
            } finally {
                client.close()
            }
        }
    }
}
