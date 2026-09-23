// NEW: V4-129 — POST /api/claude-head/{wrap,unwrap} and GET /api/claude-head, driven directly.
//
// HERMETIC BY CONSTRUCTION: ClaudeHeadRoutes' default `home` is the REAL machine's home, and an
// authorized wrap on it would write into the operator's actual ~/.claude. So this rig builds the
// routes against a @TempDir home with no bearer in front of them, for the full wrap/unwrap
// response-shape behavior — exactly the split WrappedHeadTest already uses one level down. That the
// routes are registered and bearer-guarded is proven by the control plane's ClaudeHeadRoutesWiringTest.
package splice.launch.wrap

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
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
import splice.core.config.InstallPaths
import splice.launch.HeadTrees
import splice.launch.LaunchHead
import splice.launch.LaunchSpec
import splice.launch.absentAuth
import splice.launch.launchHeadsOf
import splice.launch.runningHead
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

    private fun claudeHead(configDir: Path): LaunchHead = LaunchHead(
        head = runningHead("claude-splice"),
        auth = absentAuth("client"),
        spec = launchSpec(configDir),
    )

    // ── hermetic: ClaudeHeadRoutes directly against a @TempDir home — full wrap/unwrap behavior ──

    private inner class HermeticRig(home: Path) {
        val bin: Path = home.resolve("bin").also { Files.createDirectories(it) }
        val share: Path = home.resolve("share").also { Files.createDirectories(it) }
        val realBinary: Path = home.resolve("real-claude").also { it.writeText("#!/bin/sh\n") }
        val shim: Path = share.resolve("splice-launch").also { it.writeText("#!/usr/bin/env bash\n") }
        val configDir: Path = home.resolve(".claude-claude-splice")
        val routes = ClaudeHeadRoutes(
            heads = launchHeadsOf(claudeHead(configDir)),
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
