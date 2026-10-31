// v0.4.0 spec section 13 on the openai-chat wire, through the real HeadServer and a mock Chat Completions upstream.
// Chat has no system field and no cache breakpoints (the vendor caches the longest shared prefix), so the check is the
// prefix: the head, project and project-head prompts are system messages in that order after the client's own system
// messages and before the first user message, at the same place on every turn.
package splice.provider.openai

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptLayers
import splice.core.topology.ProjectConfig
import splice.core.topology.ProjectHeadPrompt
import splice.core.turn.WatchdogBudget
import splice.dialect.chat.ChatQuirks
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.awaitListening
import splice.head.compaction.SessionProjectLookup
import splice.head.headDeps
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val HEAD_KEY = "openrouter"
private const val HEAD_RULES = "rules from the head"
private const val PROJECT_RULES = "rules from the project"
private const val PROJECT_HEAD_RULES = "rules from the project's head"
private val RULES = listOf(HEAD_RULES, PROJECT_RULES, PROJECT_HEAD_RULES)
private const val FINISH = """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],""" +
    """"usage":{"prompt_tokens":1,"completion_tokens":1}}"""

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAiChatProjectPromptTest(@param:TempDir private val root: Path) {

    private val received = CopyOnWriteArrayList<String>()
    private val upstream: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
        server.createContext("/") { ex ->
            received += ex.requestBody.readAllBytes().decodeToString()
            val body = "data: $FINISH\n\ndata: [DONE]\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }
    private val project: Path = Files.createDirectories(root.resolve("repo"))
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private val head: HeadServer = runBlocking {
        val layers = SystemPromptLayers(
            HeadSystemPrompt(text = HEAD_RULES, source = "head:$HEAD_KEY"),
            projects = mapOf(
                "\"$project\"" to ProjectConfig(
                    systemPrompt = PROJECT_RULES,
                    heads = mapOf(HEAD_KEY to ProjectHeadPrompt(systemPrompt = PROJECT_HEAD_RULES)),
                ),
            ),
            headKey = HEAD_KEY,
        )
        val server = HeadServer(
            provider = OpenAiChatProvider(
                tuning = ProviderTuning(
                    name = ProviderName(key = HEAD_KEY, label = HEAD_KEY),
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-openrouter--",
                        models = listOf(ModelEntry("meta/llama-4", "Llama 4", contextWindow = 128_000)),
                        defaultContextWindow = 128_000,
                    ),
                    pinnedModel = "meta/llama-4",
                    auth = ApiKeyAuthProvider("OPENROUTER_API_KEY", envReader = { "or-key-123456" }),
                    locations = ProviderLocations(baseUrl = "http://127.0.0.1:${upstream.address.port}"),
                    watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                ),
                quirks = ChatQuirks(providerTag = HEAD_KEY),
            ),
            listenPort = 0,
            deps = headDeps(
                tmp = Files.createDirectories(root.resolve("head")),
                upstream = UpstreamClient(totalTimeoutMs = 30_000),
                seams = HeadDeps.HeadSeams(
                    session = HeadDeps.SessionSeams(sessionProject = SessionProjectLookup { project.resolve("src") }),
                ),
            ).let { it.copy(policy = it.policy.copy(systemPrompt = layers)) },
        )
        server.start()
        awaitListening(server.port)
        server
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        upstream.stop(0)
        client.close()
    }

    private suspend fun upstreamMessages(messages: String): List<JsonObject> {
        val before = received.size
        val reply = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "sess-project")
            setBody(
                """{"model":"claude-openrouter--meta/llama-4","stream":true,"max_tokens":64,""" +
                    """"system":"client rules","messages":$messages}""",
            )
        }.bodyAsText()
        assertTrue(reply.contains("message_stop"), "the turn completed: $reply")
        val body = Json.parseToJsonElement(received.drop(before).single()).jsonObject
        return body.getValue("messages").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.text() = getValue("content").jsonPrimitive.content

    private fun JsonObject.role() = getValue("role").jsonPrimitive.content

    private val first = """[{"role":"user","content":"carry on"}]"""
    private val second = """[{"role":"user","content":"carry on"},{"role":"assistant","content":"ok"},""" +
        """{"role":"user","content":"and again"}]"""

    @Test
    fun `the layers are system messages in order after the client's and before the first user message`() = runBlocking {
        val messages = upstreamMessages(first)

        assertEquals(listOf("system", "system", "system", "system", "user"), messages.map { it.role() })
        assertEquals(listOf("client rules") + RULES, messages.take(4).map { it.text() })
    }

    @Test
    fun `the prompts sit at the same place on every turn so the prefix holds`() = runBlocking {
        val one = upstreamMessages(first)
        val two = upstreamMessages(second)

        assertEquals(one.take(5), two.take(5), "system messages and the first user message are byte-identical")
    }
}
