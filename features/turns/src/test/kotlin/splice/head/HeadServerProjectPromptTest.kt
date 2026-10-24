// v0.4.0 spec section 13 through the REAL production path: HeadServer over HTTP to a mock upstream. A session whose
// working directory sits inside a configured [projects] root sends the project's prompt layer upstream beside the
// head's, and a session whose directory cannot be resolved sends the head's layer only.
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
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

private const val HEAD_RULES = "HEAD HOUSE RULES"
private const val PROJECT_RULES = "PROJECT HOUSE RULES"

private class ProjectAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-project", "acct-project")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerProjectPromptTest(@param:TempDir private val root: Path) {

    private val mock = MockChatGptUpstream()
    private val project: Path = Files.createDirectories(root.resolve("bot"))
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }
    private val head: HeadServer = runBlocking {
        val layers = SystemPromptLayers(
            HeadSystemPrompt(text = HEAD_RULES, source = "head:codex"),
            projects = mapOf("\"$project\"" to ProjectConfig(systemPrompt = PROJECT_RULES)),
        )
        val server = HeadServer(
            provider = TestResponsesProvider(
                tuning = ProviderTuning(
                    name = ProviderName(key = "codex", label = "claudex"),
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = ProjectAuth(),
                    locations = ProviderLocations(baseUrl = mock.baseUrl),
                    watchdog = WatchdogBudget(20.seconds, 20.seconds, 30.seconds),
                ),
                reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
            ),
            listenPort = 0,
            deps = headDeps(
                tmp = Files.createDirectories(root.resolve("head")),
                upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1),
                seams = HeadDeps.HeadSeams(
                    session = HeadDeps.SessionSeams(
                        sessionProject = SessionProjectLookup { id ->
                            project.resolve("src").takeIf { id == "sess-in" }
                        },
                    ),
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

    private suspend fun upstreamBodyFor(session: String): String {
        val before = mock.upstreamBodies.size
        val reply = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", session)
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,""" +
                    """"system":"You are a test. SCENARIO:basic $session",""" +
                    """"messages":[{"role":"user","content":"carry on"}]}""",
            )
        }.bodyAsText()
        assertTrue(reply.contains("message_stop"), "the turn completed: $reply")
        return mock.upstreamBodies.drop(before).single().second
    }

    @Test
    fun `a session inside a project root sends the project layer upstream beside the head's`() = runBlocking {
        val body = upstreamBodyFor("sess-in")

        assertTrue(body.contains(HEAD_RULES), "the head layer rides")
        assertTrue(body.contains(PROJECT_RULES), "the project layer rides")
    }

    @Test
    fun `a session with no resolvable directory sends the head layer only`() = runBlocking {
        val body = upstreamBodyFor("sess-unknown")

        assertTrue(body.contains(HEAD_RULES), "the head layer rides")
        assertFalse(body.contains(PROJECT_RULES), "no project matched, so none rides")
    }
}
