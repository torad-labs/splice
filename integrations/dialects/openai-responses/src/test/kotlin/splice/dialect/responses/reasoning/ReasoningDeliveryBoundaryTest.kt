package splice.dialect.responses.reasoning

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
import splice.upstream.ReanchorRound
import splice.upstream.TurnSignals
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private object DeliveryAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("fixture", "fixture")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fixture")
}

private class DeliveryProvider(state: Path) : ResponsesProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "claudex", label = "claudex"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = DeliveryAuth,
        locations = ProviderLocations(baseUrl = "https://fixture.invalid", stateDir = state),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.TEXT, true, null, null),
    quirks = ResponsesQuirks(
        providerTag = "claudex",
        lite = ResponsesLiteQuirks(responsesLiteModelRegex = Regex("gpt-5\\.6")),
        roundTrip = ResponsesRoundTripQuirks(reasoningCache = true),
    ),
    log = {},
) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = emptyMap()
}

private class DeliverySink : WireSink {
    val text = StringBuilder()
    val completed = mutableListOf<CustomToolSource.Completed>()
    var opaqueBlocks = 0
        private set
    private var next = 0

    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        this.text.append(text)
    }
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) {
        this.text.append(text)
    }
    override suspend fun addRedactedThinking(data: String) {
        opaqueBlocks++
    }
    override suspend fun customToolSource(event: CustomToolSource) {
        if (event is CustomToolSource.Completed) completed += event
    }
}

private object DeliveryFrames {
    fun message(id: String, text: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("id", id)
        put("role", "assistant")
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "output_text")
                        put("text", text)
                    },
                ),
            ),
        )
    }

    fun reasoning(id: String): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("id", id)
        put("encrypted_content", "cipher-$id")
        put("summary", JsonArray(emptyList()))
    }

    fun done(item: JsonObject, index: Int? = 0): JsonObject = buildJsonObject {
        put("type", "response.output_item.done")
        index?.let { put("output_index", it) }
        put("item", item)
    }

    fun delta(text: String, index: Int = 0): JsonObject = buildJsonObject {
        put("type", "response.output_text.delta")
        put("output_index", index)
        put("delta", text)
    }

    fun terminal(vararg items: JsonObject): JsonObject = buildJsonObject {
        put("type", "response.completed")
        put(
            "response",
            buildJsonObject {
                put("status", "completed")
                put("output", JsonArray(items.toList()))
            },
        )
    }

    fun exec(): JsonObject = buildJsonObject {
        put("type", "custom_tool_call")
        put("call_id", "exec_1")
        put("name", "splice_exec")
        put("input", "return 1;")
    }
}

class ReasoningDeliveryBoundaryTest(@param:TempDir private val state: Path) {
    private val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })
    private val opening = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."}]}""",
    )

    @Test
    fun `recovery resumes after delivered prose while preserving the complete native payload`() = runTest {
        val provider = DeliveryProvider(state)
        val built = provider.buildTurn(opening, false, "session")
        val sink = DeliverySink()
        val complete = DeliveryFrames.message("msg_full", "Hello world")
        val torn = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(DeliveryFrames.delta("Hello"), DeliveryFrames.done(complete)).asFlow(),
            sink,
        )
        val failure = checkNotNull(torn as? TurnOutcome.Failure)
        assertEquals("Hello", sink.text.toString())
        assertEquals(listOf(complete), checkNotNull(failure.partial).nativePrefix)
        val next = checkNotNull(provider.reanchorPolicy(built.meta))
            .continuationForFailure(ReanchorRound(built.requestBody, failure, 0))
        val added = checkNotNull(
            next,
        ).getValue("input").jsonArray.drop(built.requestBody.getValue("input").jsonArray.size)
        assertEquals(listOf("Hello"), projection(added.dropLast(1).map { it.jsonObject }))

        val tail = DeliveryFrames.message("msg_tail", " world")
        val reason = DeliveryFrames.reasoning("rs_after_answer")
        provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                DeliveryFrames.delta(" world"),
                DeliveryFrames.done(tail),
                DeliveryFrames.done(reason, 1),
                DeliveryFrames.terminal(tail, reason),
            ).asFlow(),
            sink,
        )
        assertEquals("Hello world", sink.text.toString())
        assertEquals(0, sink.opaqueBlocks)
        assertEquals(listOf("user", "Hello world", "rs_after_answer", "user"), projection(input(provider, sink)))
    }

    @Test
    fun `terminal only reasoning follows actual delta order warm and after restart`() = runTest {
        val provider = DeliveryProvider(state)
        val built = provider.buildTurn(opening, false, "session")
        val sink = DeliverySink()
        val left = DeliveryFrames.message("msg_left", "Left")
        val right = DeliveryFrames.message("msg_right", "Right")
        val reason = DeliveryFrames.reasoning("rs_terminal")
        provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                DeliveryFrames.delta("Right", 1),
                DeliveryFrames.delta("Left"),
                DeliveryFrames.done(left),
                DeliveryFrames.done(right, 1),
                DeliveryFrames.terminal(left, right, reason),
            ).asFlow(),
            sink,
        )
        assertEquals("RightLeft", sink.text.toString())
        assertEquals(0, sink.opaqueBlocks)
        for (view in listOf(provider, DeliveryProvider(state))) {
            assertEquals(listOf("user", "RightLeft", "rs_terminal", "user"), projection(input(view, sink)))
        }
    }

    @Test
    fun `reused reasoning index retains preceding unfinished prose in recovery order`() = runTest {
        val provider = DeliveryProvider(state)
        val built = provider.buildTurn(opening, false, "session")
        val sink = DeliverySink()
        val reason = DeliveryFrames.reasoning("rs_reused")
        val torn = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(DeliveryFrames.delta("Before"), DeliveryFrames.done(reason)).asFlow(),
            sink,
        )
        val failure = checkNotNull(torn as? TurnOutcome.Failure)
        assertEquals("Before", sink.text.toString())
        val next = checkNotNull(provider.reanchorPolicy(built.meta))
            .continuationForFailure(ReanchorRound(built.requestBody, failure, 0))
        val added = checkNotNull(
            next,
        ).getValue("input").jsonArray.drop(built.requestBody.getValue("input").jsonArray.size)
        assertEquals(listOf("Before", "rs_reused"), projection(added.dropLast(1).map { it.jsonObject }))
        assertEquals(0, sink.opaqueBlocks)
    }

    @Test
    fun `a done only exec without an index seals after its captured reasoning`() = runTest {
        val provider = DeliveryProvider(state)
        val built = provider.buildTurn(opening, false, "session")
        val sink = DeliverySink()
        val reason = DeliveryFrames.reasoning("rs_before_exec")
        val outcome = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                DeliveryFrames.done(reason),
                DeliveryFrames.done(DeliveryFrames.exec(), null),
                DeliveryFrames.terminal(),
            ).asFlow(),
            sink,
        )
        val success = checkNotNull(outcome as? TurnOutcome.Success)
        assertEquals(listOf(reason), checkNotNull(sink.completed.single().snapshot).handoffs.nativePrefix)
        assertEquals(listOf(reason), success.handoffs.nativePrefix)
        assertEquals(0, sink.opaqueBlocks)
    }

    private fun input(provider: DeliveryProvider, sink: DeliverySink): List<JsonObject> =
        provider.buildTurn(followup(sink.text.toString()), false, "session").requestBody.getValue("input")
            .jsonArray.map { it.jsonObject }

    private fun followup(text: String): AnthropicTurnBody = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."},""" +
            """{"role":"assistant","content":${JsonPrimitive(text)}},{"role":"user","content":"Continue."}]}""",
    )

    private fun projection(items: List<JsonObject>): List<String> = items.map { item ->
        when {
            JsonScalars.str(item, "type") == "reasoning" -> checkNotNull(JsonScalars.str(item, "id"))
            JsonScalars.str(item, "role") == "assistant" -> JsonScalars.str(item, "content")
                ?: (item["content"] as? JsonArray).orEmpty().joinToString("") {
                    JsonScalars.str(it as? JsonObject, "text").orEmpty()
                }
            else -> checkNotNull(JsonScalars.str(item, "role"))
        }
    }
}
