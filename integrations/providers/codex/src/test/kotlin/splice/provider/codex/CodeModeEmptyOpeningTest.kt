package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.JsonWire
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep
import kotlin.time.Duration.Companion.seconds

class CodeModeEmptyOpeningTest : CodeModeBridgeTestSupport() {
    @Test
    fun `image-only agent openings keep distinct callbacks and the existing persisted identities`() = runTest {
        checkOpening("""[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}}]""")
    }

    @Test
    fun `empty-text agent openings keep distinct callbacks and the existing persisted identities`() = runTest {
        checkOpening("\"\"")
    }

    private suspend fun checkOpening(content: String) {
        val scripts = List(3) {
            ArrayDeque<CodeModeStep>(listOf(CodeModeStep.Calls(listOf(call("native-call", "Read")))))
        }
        val runtime = QueuedRuntime(ArrayDeque(scripts))
        val manager = bridge(runtime)
        val provider = provider(manager)
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-6-sol","max_tokens":100,"tools":[{"name":"Read","input_schema":{"type":"object"}}],""" +
                """"messages":[{"role":"user","content":$content}]}""",
        )
        val callbacks = listOf("agent-a", "agent-b", null).map { agent ->
            val built = provider.buildTurn(body.copy(agentId = agent), compact = false, sessionId = "session")
            assertNull(built.meta.scope.conversationKey, "the real request builder has no text opening to hash")
            assertTrue(built.meta.tools.codeMode)
            val sink = RecordingSink()
            checkNotNull(built.roundInterceptor).intercept(JsonWire.string(built.requestBody), sink) {
                RoundResult.Outcome(outerOutcome())
            }
            sink.tools.single().id
        }
        assertNotEquals(callbacks[0], callbacks[1])
        assertNotEquals(callbacks[0], callbacks[2])
        assertNotEquals(callbacks[1], callbacks[2])
        // Existing persisted turn keys include the model, unlike conversation IDs. Pin the legacy values.
        val expected = setOf(
            "d01f2a2f825ceba3269318540cea3c691c8edc87e8254a5c061e66385ea15bd7",
            "381fb047aecffb9a68e851752e363f8205fee32b8eb8b5bf3c175840538d11c4",
            "c952de695090e70ba0b924c7a95e72b96f307202e75405352ad5272379036819",
        )
        assertEquals(expected, stateFiles.records().map { it.getValue("key").jsonPrimitive.content }.toSet())
        provider.onHeadStop()
    }

    private fun provider(manager: CodexCodeModeBridge): CodexProvider = CodexProvider(
        tuning = ProviderTuning(
            name = ProviderName("test", "Test"),
            catalog = ModelCatalog(
                discoveryPrefix = "test--",
                models = listOf(ModelEntry(id = "gpt-6-sol", label = "Sol", contextWindow = 400_000)),
                defaultContextWindow = 400_000,
            ),
            pinnedModel = "gpt-6-sol",
            auth = EmptyOpeningAuth,
            locations = ProviderLocations(baseUrl = "http://127.0.0.1:1"),
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.OFF, false, null, null),
        quirks = CodexQuirks().defaultQuirks().copy(
            lite = ResponsesLiteQuirks(
                responsesLiteModelRegex = Regex("gpt-6"),
                emitEmptyLiteInstructions = false,
            ),
        ),
        codeMode = CodexCodeModeWiring(bridge = manager, onlyModels = backendCodeModeOnly),
        log = {},
    )
}

private object EmptyOpeningAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fixture")
}
