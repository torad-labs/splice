// v0.4.0 spec section 13 on the anthropic-passthrough wire (the wire Kimi rides), through the real HeadServer
// and a real HTTP upstream: the project prompt and its per-head override land as trailing system blocks in the
// order head, project, project-head, after the client's own blocks. The client's cache breakpoints stay on the
// blocks that carried them, so the cached prefix is byte-identical from one turn to the next.
package splice.head

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptLayers
import splice.core.prompt.SystemPromptMode
import splice.core.topology.ProjectConfig
import splice.core.topology.ProjectHeadPrompt
import splice.core.turn.WatchdogBudget
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.compaction.SessionProjectLookup
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val HEAD_KEY = "kimi"
private const val HEAD_RULES = "rules from the head"
private const val PROJECT_RULES = "rules from the project"
private const val PROJECT_HEAD_RULES = "rules from the project's head"
private const val CLIENT_SYSTEM = """[{"type":"text","text":"billing line"},""" +
    """{"type":"text","text":"client rules","cache_control":{"type":"ephemeral"}}]"""

private val SSE_FRAMES = listOf(
    "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":1}}}""",
    "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
    "message_stop" to """{"type":"message_stop"}""",
)

private class AnthropicProjectAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("held-secret", "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerProjectPromptAnthropicTest {

    private val received = CopyOnWriteArrayList<String>()
    private val upstream: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private lateinit var project: Path
    private lateinit var appending: HeadServer
    private lateinit var replacing: HeadServer

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        upstream.createContext("/v1/messages") { ex ->
            received += ex.requestBody.readAllBytes().decodeToString()
            val body = SSE_FRAMES.joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        upstream.start()
        project = Files.createDirectories(tmp.resolve("repo"))
        appending = headWith(tmp.resolve("append"), ProjectConfig(systemPrompt = PROJECT_RULES, heads = projectHead()))
        replacing = headWith(
            tmp.resolve("replace"),
            ProjectConfig(
                systemPrompt = PROJECT_RULES,
                systemPromptMode = SystemPromptMode.REPLACE,
                heads = projectHead(),
            ),
        )
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            appending.stop()
            replacing.stop()
        }
        upstream.stop(0)
        client.close()
    }

    private fun projectHead() = mapOf(HEAD_KEY to ProjectHeadPrompt(systemPrompt = PROJECT_HEAD_RULES))

    private fun headWith(tmp: Path, config: ProjectConfig): HeadServer {
        val provider = PassthroughProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = HEAD_KEY, label = "claude-kimi"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-kimi--",
                    models = listOf(ModelEntry("kimi-k3", "Kimi K3", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "kimi-k3",
                auth = AnthropicProjectAuth(),
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:${upstream.address.port}"),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            quirks = PassthroughQuirks(providerTag = "claude-kimi"),
        )
        val layers = SystemPromptLayers(
            HeadSystemPrompt(text = HEAD_RULES, source = "head:$HEAD_KEY"),
            projects = mapOf("\"$project\"" to config),
            headKey = HEAD_KEY,
        )
        val server = HeadServer(
            provider = provider,
            listenPort = 0,
            deps = headDeps(
                tmp = Files.createDirectories(tmp),
                upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1),
                seams = HeadDeps.HeadSeams(
                    session = HeadDeps.SessionSeams(
                        sessionProject = SessionProjectLookup { project.resolve("src") },
                    ),
                ),
            ).let { it.copy(policy = it.policy.copy(systemPrompt = layers)) },
        )
        runBlocking { server.start() }
        awaitListening(server.port)
        return server
    }

    private suspend fun turn(head: HeadServer, user: String): JsonObject {
        val before = received.size
        val reply = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "sess-project")
            setBody(
                """{"model":"claude-kimi--kimi-k3","stream":true,"max_tokens":64,"system":$CLIENT_SYSTEM,""" +
                    """"messages":[{"role":"user","content":[{"type":"text","text":"$user",""" +
                    """"cache_control":{"type":"ephemeral"}}]}]}""",
            )
        }.bodyAsText()
        assertTrue(reply.contains("message_stop"), "the turn completed: $reply")
        return Json.parseToJsonElement(received.drop(before).single()).jsonObject
    }

    private fun JsonArray.texts() = map { it.jsonObject.getValue("text").jsonPrimitive.content }

    @Test
    fun `appended layers follow the client's blocks in head, project, project-head order and add no breakpoint`() =
        runBlocking {
            val system = turn(appending, "first").getValue("system").jsonArray

            assertEquals(
                listOf("billing line", "client rules", HEAD_RULES, PROJECT_RULES, PROJECT_HEAD_RULES),
                system.texts(),
            )
            assertEquals(
                """{"type":"ephemeral"}""",
                system[1].jsonObject.getValue("cache_control").toString(),
                "the client's breakpoint stays on the block that carried it",
            )
            assertTrue(
                system.filterIndexed { index, _ -> index != 1 }.none { "cache_control" in it.jsonObject },
                "no layer carries a breakpoint of its own",
            )
        }

    @Test
    fun `the system field is byte-identical from one turn to the next so the cached prefix holds`() = runBlocking {
        val first = turn(appending, "first").getValue("system").toString()
        val second = turn(appending, "a different second message").getValue("system").toString()

        assertEquals(first, second)
    }

    @Test
    fun `a project that replaces drops the client's blocks and the head layer, and the project-head still follows`() =
        runBlocking {
            val body = turn(replacing, "first")
            val system = body.getValue("system").jsonArray

            assertEquals(listOf(PROJECT_RULES, PROJECT_HEAD_RULES), system.texts())
            assertFalse(system.toString().contains(HEAD_RULES), "a replace drops every earlier layer")
            assertTrue(
                body.getValue("messages").toString().contains("cache_control"),
                "the message breakpoint is not the system field's to move",
            )
        }
}
