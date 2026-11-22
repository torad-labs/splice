package splice.app.desktop

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.catalog.CatalogBuild
import splice.app.desktop.catalog.CatalogFile
import splice.app.desktop.catalog.DesktopProviders
import splice.app.desktop.catalog.PublishedCatalog
import splice.app.desktop.catalog.SelectorAliases
import splice.app.desktop.lane.TerminalDoor
import splice.app.desktop.record.DesktopKey
import splice.app.desktop.record.DesktopRecords
import splice.app.provider.AntigravityProvider
import splice.app.provider.ServingProviders
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.provider.antigravity.door.AntigravityDoorEngine
import java.nio.file.Files
import java.nio.file.Path

class DesktopDoorAntigravityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `Desktop Chat routes claude-antigravity through agy while CLI and terminal are refused`() =
        UserHome.within(tempDir.resolve("home")) {
            testApplication {
                val harness = buildHarness()
                harness.providers.publish()
                routing {
                    post("/door/v1/messages") { harness.door.messages(call) }
                    post("/door/v1/messages/count_tokens") { harness.door.countTokens(call) }
                }
                val chatBody =
                    """{"model":"claude-antigravity--gem-4-neon","max_tokens":128,""" +
                        """"stream":true,"messages":[{"role":"user","content":"hi"}]}"""
                try {
                    val chatRes = client.post("/door/v1/messages") {
                        header(HttpHeaders.Authorization, "Bearer ${harness.desktopKey.get()}")
                        header(HttpHeaders.UserAgent, "Anthropic/JS 0.39.0")
                        contentType(ContentType.Application.Json)
                        setBody(chatBody)
                    }
                    assertEquals(HttpStatusCode.OK, chatRes.status)
                    val sse = chatRes.bodyAsText()
                    assertTrue(sse.contains("Hello from Gemini 4 Neon"), sse)
                    assertTrue(sse.contains("event: message_start") && sse.contains("event: message_stop"), sse)

                    val countRes = client.post("/door/v1/messages/count_tokens") {
                        header(HttpHeaders.Authorization, "Bearer ${harness.desktopKey.get()}")
                        contentType(ContentType.Application.Json)
                        setBody(chatBody)
                    }
                    assertEquals(HttpStatusCode.OK, countRes.status)
                    assertTrue(countRes.bodyAsText().contains("input_tokens"))

                    val cliRes = client.post("/door/v1/messages") {
                        header(HttpHeaders.Authorization, "Bearer ${harness.desktopKey.get()}")
                        header(HttpHeaders.UserAgent, "claude-cli/2.1.330 (external, cli)")
                        contentType(ContentType.Application.Json)
                        setBody(chatBody)
                    }
                    assertEquals(HttpStatusCode.BadRequest, cliRes.status)
                    assertTrue(cliRes.bodyAsText().contains("runs only in Claude Desktop"))

                    val termRes = client.post("/door/v1/messages") {
                        header(HttpHeaders.Authorization, "Bearer ${harness.termToken}")
                        contentType(ContentType.Application.Json)
                        setBody(chatBody)
                    }
                    assertEquals(HttpStatusCode.BadRequest, termRes.status)
                    assertTrue(termRes.bodyAsText().contains("no model is in the door"))
                } finally {
                    harness.close()
                }
            }
        }

    private suspend fun buildHarness(): Harness {
        val script = writeFakeAgy()
        val log = LogSink {}
        val statePaths = StatePaths(baseOverride = tempDir.resolve("state"))
        val desktopKey = DesktopKey(statePaths.desktopKeyFile, log)
        val records = DesktopRecords(statePaths.desktopFile, log).also { r ->
            r.update { it.copy(on = true) }
        }
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-antigravity--",
            pinnedModel = "gemini-4-neon",
            models = listOf(ModelEntry("gemini-4-neon", "Gemini 4 Neon", contextWindow = 1_048_576L)),
            defaultContextWindow = 1_048_576L,
        )
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = AntigravityDoorEngine(engineScope, Dispatchers.IO, log, agy = listOf(script.toString()))
        val provider = AntigravityProvider(catalog, ConfigService(statePaths), engine)
        val published = PublishedCatalog(CatalogBuild(), CatalogFile(statePaths.modelCatalogFile, log), Dispatchers.IO)
        val providers = DesktopProviders(
            serving = ServingProviders { emptyList() },
            desktopOnly = ServingProviders { listOf(provider) },
            aliases = SelectorAliases(emptyList()),
            published = published,
        )
        val terminal = TerminalDoor(Topology(), statePaths, log)
        val (_, terminalCaller) = terminal.open(providers, "http://127.0.0.1:3096/door")
        val launchSpec = requireNotNull(terminal.launch())
        val termToken = requireNotNull(launchSpec.env["ANTHROPIC_AUTH_TOKEN"])
        val door = DesktopDoor(
            callers = listOf(DesktopCaller(desktopKey, records, log), terminalCaller),
            providers = providers,
        )
        return Harness(door, providers, desktopKey, termToken, engine, engineScope)
    }

    private fun writeFakeAgy(): Path {
        val script = tempDir.resolve("fake-agy.sh")
        Files.writeString(
            script,
            """
            #!/usr/bin/env bash
            set -euo pipefail
            while IFS= read -r line; do
              if [[ "${'$'}line" == *'"method":"initialize"'* ]]; then
                id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
                echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{\"promptCapabilities\":{\"image\":true}}}}"
              elif [[ "${'$'}line" == *'"method":"session/new"'* ]]; then
                id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
                echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{\"sessionId\":\"sess-door\",\"configOptions\":[{\"id\":\"model\",\"currentValue\":\"gemini-4-neon\",\"options\":[{\"value\":\"gemini-4-neon\",\"name\":\"Gemini 4 Neon\"}]}]}}"
              elif [[ "${'$'}line" == *'"method":"session/set_config_option"'* ]]; then
                id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
                echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{}}"
              elif [[ "${'$'}line" == *'"method":"session/prompt"'* ]]; then
                id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
                echo "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"sess-door\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Hello from Gemini 4 Neon\"}}}}"
                echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":8,\"outputTokens\":6}}}"
              fi
            done
            """.trimIndent() + "\n",
        )
        script.toFile().setExecutable(true)
        return script
    }

    private class Harness(
        val door: DesktopDoor,
        val providers: DesktopProviders,
        val desktopKey: DesktopKey,
        val termToken: String,
        private val engine: AntigravityDoorEngine,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        override fun close() {
            engine.close()
            scope.cancel()
        }
    }
}
