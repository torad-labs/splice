// NEW: V4-129 — GET/POST /api/claude-head{,/wrap,/unwrap}.
//
// TWO RIGS, deliberately: `wired` builds a REAL ControlServer (the established "prove the routes
// are registered and bearer-guarded" convention — a lost registration line or a route outside
// guarded() fails HERE, not in production) but never drives a wrap/unwrap PAST the 401 the guard
// answers without a bearer, because ClaudeHeadRoutes' default `home` is the REAL machine's home and
// an authorized wrap on it would write into the operator's actual ~/.claude. `hermetic` builds
// ClaudeHeadRoutes directly against a @TempDir home (no ControlServer, no bearer — that guard is
// what `wired` already proves) for the full wrap/unwrap response-shape behavior, exactly the split
// WrappedHeadTest already uses one level down.
package splice.control.api.fleet

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudePolicy
import splice.client.wrap.WrapStateStore
import splice.client.wrap.WrappedHead
import splice.control.CompactView
import splice.control.ControlServer
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadTrees
import splice.control.HeadUsageSource
import splice.control.LaunchSpec
import splice.control.ManagedHead
import splice.control.RateLimitView
import splice.control.UsageView
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.InstallPaths
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.post as routePost

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L

class ClaudeHeadRoutesTest {

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
        val port = ServerSocket(0).use { it.localPort }
        val control = ControlServer(
            port = port,
            heads = mapOf("claude-splice" to managedHead(tmp.resolve(".claude-claude-splice"))),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
        )
        control.start()
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

    // ── hermetic: ClaudeHeadRoutes directly against a @TempDir home — full wrap/unwrap behavior ──

    private inner class HermeticRig(home: Path) {
        val bin: Path = home.resolve("bin").also { Files.createDirectories(it) }
        val share: Path = home.resolve("share").also { Files.createDirectories(it) }
        val realBinary: Path = home.resolve("real-claude").also { it.writeText("#!/bin/sh\n") }
        val shim: Path = share.resolve("splice-launch").also { it.writeText("#!/usr/bin/env bash\n") }
        val configDir: Path = home.resolve(".claude-claude-splice")
        val routes = ClaudeHeadRoutes(
            heads = mapOf("claude-splice" to managedHead(configDir)),
            home = home,
            wrappedHead = WrappedHead(
                home = home,
                installPaths = InstallPaths(binOverride = bin, shareOverride = share),
                stateStore = WrapStateStore(file = home.resolve("state/claude-head-wrap.json")),
            ),
        )

        fun linkCmdToReal() = Files.createSymbolicLink(bin.resolve("claude"), realBinary)
    }

    private fun serveHermetic(home: Path, test: suspend (port: Int, rig: HermeticRig) -> Unit) {
        val rig = HermeticRig(home)
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            routing {
                routeGet("/api/claude-head") { rig.routes.status(call) }
                routePost("/api/claude-head/wrap") { rig.routes.wrap(call) }
                routePost("/api/claude-head/unwrap") { rig.routes.unwrap(call) }
            }
        }
        server.start(wait = false)
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (runCatching { ServerSocket(port).close() }.isSuccess) delay(POLL_MS)
                    test(port, rig)
                }
            }
        } finally {
            client.close()
            server.stop(100, 500)
        }
    }

    private suspend fun postJson(port: Int, path: String): kotlinx.serialization.json.JsonObject {
        val client = HttpClient(CIO) { expectSuccess = false }
        return try {
            Json.parseToJsonElement(client.post("http://127.0.0.1:$port$path").bodyAsText()).jsonObject
        } finally {
            client.close()
        }
    }

    @Test
    fun `wrap through the route answers ok with both backup paths, and mode flips to wrapped on the next GET`(
        @TempDir home: Path,
    ) {
        serveHermetic(home) { port, rig ->
            rig.linkCmdToReal()
            val response = postJson(port, "/api/claude-head/wrap")
            assertTrue(response["ok"]!!.jsonPrimitive.boolean, "$response")
            assertTrue(response.containsKey("settings_backup_path"))
            assertTrue(response.containsKey("claude_json_backup_path"))

            val client = HttpClient(CIO) { expectSuccess = false }
            val status = try {
                Json.parseToJsonElement(client.get("http://127.0.0.1:$port/api/claude-head").bodyAsText()).jsonObject
            } finally {
                client.close()
            }
            assertEquals("wrapped", status["mode"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `wrap without an existing claude to preserve answers a named refusal, not a 500`(@TempDir home: Path) {
        serveHermetic(home) { port, _ ->
            val response = postJson(port, "/api/claude-head/wrap")
            assertEquals(setOf("error"), response.keys, "$response")
        }
    }

    @Test
    fun `unwrap when never wrapped answers a named refusal`(@TempDir home: Path) {
        serveHermetic(home) { port, rig ->
            rig.linkCmdToReal()
            val response = postJson(port, "/api/claude-head/unwrap")
            assertTrue(response.containsKey("error"), "$response")
        }
    }
}
