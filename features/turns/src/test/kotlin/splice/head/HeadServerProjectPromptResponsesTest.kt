// v0.4.0 spec section 13 on the openai-responses wire, through the REAL HeadServer and a mock Responses upstream: the
// head, project and project-head prompts ride as developer items in that order, the client's own system text is not
// touched, and the prompts sit at the same place in the input on every turn so the cached prefix stays stable.
package splice.head

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import splice.core.topology.ProjectConfig
import splice.core.topology.ProjectHeadPrompt
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.head.compaction.SessionProjectLookup
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val HEAD_KEY = "codex"
private const val HEAD_RULES = "rules from the head"
private const val PROJECT_RULES = "rules from the project"
private const val PROJECT_HEAD_RULES = "rules from the project's head"
private val RULES = listOf(HEAD_RULES, PROJECT_RULES, PROJECT_HEAD_RULES)

private class ResponsesProjectAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-project", "acct-project")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerProjectPromptResponsesTest(@param:TempDir private val root: Path) {

    private val mock = MockChatGptUpstream()
    private val project: Path = Files.createDirectories(root.resolve("bot"))
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
            provider = TestResponsesProvider(
                tuning = ProviderTuning(
                    name = ProviderName(key = HEAD_KEY, label = "claudex"),
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = ResponsesProjectAuth(),
                    locations = ProviderLocations(baseUrl = mock.baseUrl),
                    watchdog = WatchdogBudget(20.seconds, 20.seconds, 30.seconds),
                ),
                reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
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
        mock.stop()
        client.close()
    }

    private suspend fun upstreamBody(messages: String): JsonObject {
        val before = mock.upstreamBodies.size
        val reply = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "sess-project")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,""" +
                    """"system":"You are a test. SCENARIO:basic","messages":$messages}""",
            )
        }.bodyAsText()
        assertTrue(reply.contains("message_stop"), "the turn completed: $reply")
        return Json.parseToJsonElement(mock.upstreamBodies.drop(before).single().second).jsonObject
    }

    private fun JsonObject.input() = getValue("input").jsonArray.map { it.jsonObject }

    private fun JsonObject.ruleIndexes(): List<Int> = input().mapIndexedNotNull { index, item ->
        index.takeIf { item["role"]?.jsonPrimitive?.content == "developer" && ruleText(item) in RULES }
    }

    private fun ruleText(item: JsonObject): String? = (item["content"] as? JsonPrimitive)?.content

    private val first = """[{"role":"user","content":"carry on"}]"""
    private val second = """[{"role":"user","content":"carry on"},{"role":"assistant","content":"ok"},""" +
        """{"role":"user","content":"and again"}]"""

    @Test
    fun `the layers ride as developer items in head, project, project-head order`() = runBlocking {
        val body = upstreamBody(first)

        assertEquals(RULES, body.ruleIndexes().map { ruleText(body.input()[it]) })
    }

    @Test
    fun `the prompts sit at the same place with the same prefix before them on every turn`() = runBlocking {
        val one = upstreamBody(first)
        val two = upstreamBody(second)

        val at = one.ruleIndexes().first()
        assertEquals(at, two.ruleIndexes().first(), "the prompts stay put while the conversation grows")
        assertEquals(one.input().take(at), two.input().take(at), "everything before them is byte-identical")
        assertEquals(one["prompt_cache_key"], two["prompt_cache_key"], "the cache key is the conversation's")
    }
}
