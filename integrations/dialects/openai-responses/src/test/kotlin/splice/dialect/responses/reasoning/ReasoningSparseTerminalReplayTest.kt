package splice.dialect.responses.reasoning

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget
import splice.core.util.JsonScalars
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRoundTripQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.TurnSignals
import splice.upstream.sse.WireSink
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private object SparseOrderAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("fixture-token", "fixture-account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fixture")
}

private class SparseOrderProvider(state: Path, cache: Boolean = true, replay: Boolean = true) : ResponsesProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "fixture", label = "fixture"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = SparseOrderAuth,
        locations = ProviderLocations(baseUrl = "https://fixture.invalid", stateDir = state),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.OFF, replay, null, null),
    quirks = ResponsesQuirks(
        providerTag = "fixture",
        lite = ResponsesLiteQuirks(),
        roundTrip = ResponsesRoundTripQuirks(reasoningCache = cache),
    ),
    log = {},
) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = emptyMap()
}

private class SparseOrderSink : WireSink {
    private var next = 0
    val opaque = mutableListOf<String>()
    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) { opaque += data }
}

class ReasoningSparseTerminalReplayTest(@param:TempDir private val state: Path) {
    @ParameterizedTest
    @ValueSource(strings = ["B", "C,A", "C,B,A"])
    fun `actual sparse terminal reasoning keeps observed order in warm and restarted requests`(
        terminal: String,
    ) = runTest {
        val provider = SparseOrderProvider(state)
        val observed = listOf("A", "B", "C")
        val terminalIds = terminal.split(',')
        val expected = (if (terminalIds.toSet() == observed.toSet()) terminalIds else observed).map { "rs_$it" }
        drive(provider, observed, terminalIds)
        assertEquals(expected, ids(input(provider)))
        assertEquals(expected, ids(input(provider)))
        assertEquals(expected, ids(input(SparseOrderProvider(state))))
    }

    @Test
    fun `cached same round sparse retries never combine into an invented terminal order`() = runTest {
        val expected = listOf("rs_A", "rs_B", "rs_C")
        drive(SparseOrderProvider(state), listOf("A", "B", "C"), emptyList())
        val firstRetry = SparseOrderProvider(state)
        drive(firstRetry, listOf("A"), listOf("C", "A"))
        assertEquals(expected, ids(input(firstRetry)))
        assertEquals(expected, ids(input(SparseOrderProvider(state))))
        val secondRetry = SparseOrderProvider(state)
        drive(secondRetry, listOf("A"), listOf("B"))
        assertEquals(expected, ids(input(secondRetry)))
        assertEquals(expected, ids(input(SparseOrderProvider(state))))
        val completeRetry = SparseOrderProvider(state)
        drive(completeRetry, listOf("A"), listOf("C", "B", "A"))
        val authoritative = listOf("rs_C", "rs_B", "rs_A")
        assertEquals(authoritative, ids(input(completeRetry)))
        assertEquals(authoritative, ids(input(SparseOrderProvider(state))))
        val laterSparse = SparseOrderProvider(state)
        drive(laterSparse, listOf("A"), listOf("B"))
        assertEquals(authoritative, ids(input(laterSparse)))
        assertEquals(authoritative, ids(input(SparseOrderProvider(state))))
    }

    @Test
    fun `off off warm fragments preserve input bytes and config without replaying captured reasoning`() = runTest {
        val off = SparseOrderProvider(state.resolve("off"), cache = false, replay = false)
        val capture = SparseOrderProvider(state.resolve("capture"), cache = true, replay = false)
        val body = fragmentRequest()
        val baseline = input(off, body)
        assertEquals(baseline.toString(), input(off, body).toString())
        assertEquals(baseline.toString(), input(capture, body).toString())
        val offBuilt = off.buildTurn(body, compact = false, sessionId = "fixture-session")
        val captureBuilt = capture.buildTurn(body, compact = false, sessionId = "fixture-session")
        assertEquals(offBuilt.meta.scope.conversationKey, captureBuilt.meta.scope.conversationKey)
        assertFalse(offBuilt.requestBody.toString().contains("reasoning.encrypted_content"))
        assertTrue(captureBuilt.requestBody.toString().contains("reasoning.encrypted_content"))
        drive(off, listOf("A", "B"), listOf("B"), body)
        assertEquals(baseline.toString(), input(off, body).toString())
    }

    @Test
    fun `replay on with persistence off retains actual reasoning only in session memory`() = runTest {
        val provider = SparseOrderProvider(state, cache = false, replay = true)
        drive(provider, listOf("A", "B"), emptyList())
        assertEquals(listOf("rs_A", "rs_B"), ids(input(provider)))
        assertEquals(emptyList<String>(), ids(input(SparseOrderProvider(state, cache = false, replay = true))))
    }

    private suspend fun drive(
        provider: ResponsesProvider,
        observed: List<String>,
        terminal: List<String>,
        body: AnthropicTurnBody = request(),
    ) {
        val built = provider.buildTurn(body, compact = false, sessionId = "fixture-session")
        val frames = observed.mapIndexed { index, id ->
            buildJsonObject {
                put("type", "response.output_item.done")
                put("output_index", index)
                put("item", reasoning(id))
            }
        } + buildJsonObject {
            put("type", "response.completed")
            put(
                "response",
                buildJsonObject {
                    put("status", "completed")
                    put("output", JsonArray(terminal.map(::reasoning)))
                },
            )
        }
        val sink = SparseOrderSink()
        val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })
        val result = provider.streamTranslator(built.meta, signals).driveTurn(frames.asFlow(), sink)
        assertTrue(result is TurnOutcome.Success, result.toString())
        assertEquals(emptyList<String>(), sink.opaque)
    }

    private fun request(): AnthropicTurnBody = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","max_tokens":1000,"messages":[
            {"role":"user","content":"Start 🚀"},
            {"role":"assistant","content":"Existing prose."},
            {"role":"user","content":"Continue."}]}""",
    )

    private fun fragmentRequest(): AnthropicTurnBody = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","max_tokens":1000,"messages":[
            {"role":"user","content":"Start 🚀"},
            {"role":"assistant","content":[{"type":"text","text":"Read now."},
                {"type":"tool_use","id":"t1","name":"read","input":{"path":"fixture.txt"}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":[
                {"type":"text","text":"Result 🧭"},
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}}]},
                {"type":"text","text":"Peer update."}]}]}""",
    )

    private fun input(provider: ResponsesProvider, body: AnthropicTurnBody = request()): JsonArray = provider.buildTurn(
        body,
        compact = false,
        sessionId = "fixture-session",
    ).requestBody.getValue("input").jsonArray

    private fun ids(input: JsonArray): List<String> = input.map { it.jsonObject }
        .filter { JsonScalars.str(it, "type") == "reasoning" }.map { checkNotNull(JsonScalars.str(it, "id")) }

    private fun reasoning(id: String): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_$id")
        put("encrypted_content", "fixture-$id")
        put("summary", JsonArray(emptyList()))
    }
}
