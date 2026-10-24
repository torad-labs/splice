package splice.dialect.responses.reasoning

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.REASONING_DIR
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.parse.AnthropicTurnBody
import splice.core.reasoning.ReasoningHistory
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget
import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRoundTripQuirks
import splice.dialect.responses.request.ResponsesStableIds
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.TurnSignals
import splice.upstream.sse.WireSink
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private object ReplayRequestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("fixture-token", "fixture-account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fixture")
}

private class ReplayRequestProvider(state: Path, cache: Boolean) : ResponsesProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "claudex", label = "claudex"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = ReplayRequestAuth,
        locations = ProviderLocations(baseUrl = "https://fixture.invalid", stateDir = state),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.TEXT, true, null, null),
    quirks = ResponsesQuirks(
        lite = ResponsesLiteQuirks(),
        providerTag = "claudex",
        roundTrip = ResponsesRoundTripQuirks(reasoningCache = cache),
    ),
    log = {},
) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = emptyMap()
}

/** Replays any legacy envelope the actual translator delivered, rather than manufacturing one for input. */
private class ReplayRequestSink : WireSink {
    val envelopes = mutableListOf<JsonObject>()
    private var next = 0
    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) {
        envelopes += buildJsonObject {
            put("type", "redacted_thinking")
            put("data", data)
        }
    }
}

class ReasoningReplayRequestRegressionTest(@param:TempDir private val state: Path) {
    private val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })
    private val opening = """{"role":"user","content":"Inspect the files."}"""
    private val terminal = event("""{"type":"response.completed","response":{"status":"completed","output":[]}}""")

    @Test
    fun `replay enabled retains prior reasoning when the cache is disabled`() = runTest {
        for (cache in listOf(true, false)) {
            val provider = ReplayRequestProvider(state.resolve(if (cache) "cached" else "uncached"), cache)
            val sink = ReplayRequestSink()
            drive(provider, request(opening), reasoning("rs_plan") + prose("Inspecting.") + call("call_1", 2), sink)
            val envelopes = sink.envelopes.map(JsonObject::toString)
            val assistantBlocks = (envelopes + listOf(text("Inspecting."), tool("call_1"))).joinToString(",")
            val next = request(
                "$opening,${assistant(assistantBlocks)},${result("call_1", "file contents")}," +
                    """{"role":"user","content":"Continue the inspection."}""",
            )
            val input = input(provider, next)
            assertEquals(
                listOf("rs_plan"),
                reasoningIds(input),
                "replay is enabled independently of the reasoning-cache setting (cache=$cache)",
            )
            assertTrue(input.any { it["type"]?.jsonPrimitive?.content == "function_call_output" })
        }
    }

    @Test
    fun `clearing an earlier tool result retains reasoning around later prose and calls`() = runTest {
        val provider = ReplayRequestProvider(state, cache = true)
        drive(provider, request(opening), reasoning("rs_initial") + call("call_1"))
        val firstHistory = "$opening,${assistant(tool("call_1"))},${result("call_1", "original file contents")}"
        drive(
            provider,
            request(firstHistory),
            reasoning("rs_after_result") + prose("Reviewed the file.") +
                reasoning("rs_after_preface", 2) + call("call_2", 3),
        )
        val suffix = ",${assistant("${text("Reviewed the file.")},${tool("call_2")}")},${result("call_2", "done")}," +
            """{"role":"user","content":"Continue the inspection."}"""
        val intact = input(provider, request(firstHistory + suffix))
        assertEquals(listOf("rs_initial", "rs_after_result", "rs_after_preface"), reasoningIds(intact))

        // Claude Code microcompaction preserves the tool-use identity while clearing the old result payload.
        val clearedHistory = "$opening,${assistant(tool("call_1"))}," +
            result("call_1", "[Old tool result content cleared]")
        val cleared = input(provider, request(clearedHistory + suffix))
        assertEquals(
            "[Old tool result content cleared]",
            cleared.single {
                it["call_id"]?.jsonPrimitive?.content == "call_1" &&
                    it["type"]?.jsonPrimitive?.content == "function_call_output"
            }.getValue("output").jsonPrimitive.content,
            "reasoning replay must not restore the cleared tool-result contents",
        )
        assertEquals(
            intact.filter { it["type"]?.jsonPrimitive?.content == "reasoning" },
            cleared.filter { it["type"]?.jsonPrimitive?.content == "reasoning" },
            "clearing an old tool result must not drop later retained reasoning",
        )
        val resultAt = cleared.indexOfFirst { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        val afterResultAt = cleared.indexOfFirst { it["id"]?.jsonPrimitive?.content == "rs_after_result" }
        val prefaceAt = cleared.indexOfFirst { it["role"]?.jsonPrimitive?.content == "assistant" }
        val afterPrefaceAt = cleared.indexOfFirst { it["id"]?.jsonPrimitive?.content == "rs_after_preface" }
        val callAt = cleared.indexOfFirst { it["call_id"]?.jsonPrimitive?.content == "call_2" }
        assertTrue(
            resultAt < afterResultAt && afterResultAt < prefaceAt &&
                prefaceAt < afterPrefaceAt && afterPrefaceAt < callAt,
        )
    }

    @Test
    fun `result proofs learned before the first reasoning item survive restart and clearing`() = runTest {
        val provider = ReplayRequestProvider(state, cache = true)
        drive(provider, request(opening), call("call_1"))
        val prefix = "$opening,${assistant(tool("call_1"))},"
        drive(
            provider,
            request(prefix + result("call_1", "original file contents")),
            reasoning("rs_first") + prose("Checked."),
        )
        val tail = ",${assistant(text("Checked."))}," +
            """{"role":"user","content":"Continue the inspection."}"""
        val restarted = ReplayRequestProvider(state, cache = true)
        val cleared = input(
            restarted,
            request(prefix + result("call_1", "[Old tool result content cleared]") + tail),
        )
        assertEquals(listOf("rs_first"), reasoningIds(cleared))
        assertEquals(
            "[Old tool result content cleared]",
            cleared.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
                .getValue("output").jsonPrimitive.content,
        )
    }

    @Test
    fun `changed text under the same tool call id does not replay later reasoning`() = runTest {
        changedResultDropsReasoning(result("call_1", "first contents"), result("call_1", "different contents"), "text")
    }

    @Test
    fun `changed pixels under the same tool call id and caption do not replay later reasoning`() = runTest {
        changedResultDropsReasoning(imageResult("AA=="), imageResult("BB=="), "image")
    }

    @Test
    fun `clearing one result does not excuse changing another result in the same history`() = runTest {
        val provider = ReplayRequestProvider(state, cache = true)
        drive(provider, request(opening), reasoning("rs_initial") + call("call_1"))
        val first = "$opening,${assistant(tool("call_1"))},${result("call_1", "first contents")}"
        drive(provider, request(first), call("call_2"))
        val second = "$first,${assistant(tool("call_2"))},${result("call_2", "second contents")}"
        drive(provider, request(second), reasoning("rs_after_results") + prose("Checked."))
        val tail = ",${assistant(text("Checked."))}," +
            """{"role":"user","content":"Continue the inspection."}"""
        val expected = listOf("rs_initial", "rs_after_results")
        assertEquals(expected, reasoningIds(input(provider, request(second + tail))))
        val cleared = "$opening,${assistant(tool("call_1"))}," +
            result("call_1", "[Old tool result content cleared]")
        val clearedOnly = "$cleared,${assistant(tool("call_2"))},${result("call_2", "second contents")}"
        assertEquals(expected, reasoningIds(input(provider, request(clearedOnly + tail))))
        val changed = "$cleared,${assistant(tool("call_2"))},${result("call_2", "different second contents")}"
        assertEquals(
            listOf("rs_initial"),
            reasoningIds(input(provider, request(changed + tail))),
            "one explicit clearing marker cannot validate another tool result's changed payload",
        )
    }

    private suspend fun changedResultDropsReasoning(original: String, changed: String, scope: String) {
        val provider = ReplayRequestProvider(state.resolve(scope), cache = true)
        drive(provider, request(opening), reasoning("rs_initial") + call("call_1"))
        val prefix = "$opening,${assistant(tool("call_1"))},"
        drive(provider, request(prefix + original), reasoning("rs_after_result") + prose("Checked."))
        val tail = ",${assistant(text("Checked."))}," +
            """{"role":"user","content":"Continue the inspection."}"""
        assertEquals(
            listOf("rs_initial", "rs_after_result"),
            reasoningIds(input(provider, request(prefix + original + tail))),
        )
        assertEquals(
            listOf("rs_initial"),
            reasoningIds(input(provider, request(prefix + changed + tail))),
            "a different non-cleared result payload is a different history even when the call id is unchanged",
        )
    }

    @Test
    fun `persisted full-result positions replay on upgrade and survive later clearing after a verified lookup`() {
        val firstHistory = "$opening,${assistant(tool("call_1"))},${result("call_1", "original file contents")}"
        val prefix = request("$firstHistory,${assistant(text("Reviewed. "))}")
        persistLegacyReasoning(prefix)
        val suffix = ",${assistant(text("Reviewed. More."))}," +
            """{"role":"user","content":"Continue the inspection."}"""
        val clearedHistory = "$opening,${assistant(tool("call_1"))}," +
            result("call_1", "[Old tool result content cleared]")
        val provider = ReplayRequestProvider(state, cache = true)
        assertTrue(
            reasoningIds(input(provider, request(clearedHistory + suffix))).isEmpty(),
            "an old payload hash cannot recover already-cleared history before an exact original-prefix lookup",
        )
        assertEquals(
            listOf("rs_legacy"),
            reasoningIds(input(provider, request(firstHistory + suffix))),
            "an upgraded provider must read unchanged history from an existing full-result cache",
        )
        val restarted = ReplayRequestProvider(state, cache = true)
        val cleared = input(restarted, request(clearedHistory + suffix))
        assertEquals(
            listOf("rs_legacy"),
            reasoningIds(cleared),
            "a verified old-prefix match must persist a stable alias before later clearing and restart",
        )
        assertEquals(
            "[Old tool result content cleared]",
            cleared.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
                .getValue("output").jsonPrimitive.content,
        )
        val changedCall = "$opening,${assistant(
            tool("different_call"),
        )}," + result("different_call", "original file contents")
        assertTrue(reasoningIds(input(restarted, request(changedCall + suffix))).isEmpty())
    }

    /** Persist the pre-upgrade format with the complete tool-result payload in its history digest. */
    private fun persistLegacyReasoning(prefix: AnthropicTurnBody) {
        val item = event(
            """{"type":"reasoning","id":"rs_legacy","encrypted_content":"fixture-legacy","summary":[]}""",
        )
        val envelope = checkNotNull(ReasoningReplay.encodeReasoningEnvelope(item))
        val key = checkNotNull(
            ReasoningCachePolicy().conversationKey(
                "fixture-session",
                ResponsesStableIds().reasoningStorageKey(prefix.typed),
            ),
        )
        val index = ReasoningPositionIndex().index(
            legacyHistory(prefix).key,
            envelope,
            ReasoningItemOrder("legacy-round", 0, true, "rs_legacy"),
        )
        ReasoningCache(
            files = ReasoningCacheFiles(state.resolve(REASONING_DIR)) {},
        ).put(key, listOf(index), listOf(envelope))
    }

    /** The f940 cursor grammar, independent of the new stable-result cursor. */
    private fun legacyHistory(body: AnthropicTurnBody): ReasoningHistory = ReasoningHistory().also { history ->
        body.typed.messages.forEach { message ->
            message.content.forEach { block ->
                when (block) {
                    is TextBlock -> history.text(message.role, block.text)
                    is ThinkingBlock, is RedactedThinkingBlock -> Unit
                    is ToolUseBlock -> history.block(
                        message.role,
                        "tool_use",
                        Json.encodeToJsonElement(ContentBlock.serializer(), block),
                    )
                    is ImageBlock, is DocumentBlock, is ToolResultBlock, is UnknownBlock -> history.block(
                        message.role,
                        "content",
                        Json.encodeToJsonElement(ContentBlock.serializer(), block),
                    )
                }
            }
        }
    }

    private suspend fun drive(
        provider: ResponsesProvider,
        body: AnthropicTurnBody,
        frames: List<JsonObject>,
        sink: ReplayRequestSink = ReplayRequestSink(),
    ) {
        val built = provider.buildTurn(body, compact = false, sessionId = "fixture-session")
        val outcome = provider.streamTranslator(built.meta, signals).driveTurn((frames + terminal).asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Success)
    }

    private fun input(provider: ResponsesProvider, body: AnthropicTurnBody): List<JsonObject> =
        provider.buildTurn(body, compact = false, sessionId = "fixture-session")
            .requestBody.getValue("input").jsonArray.map { it.jsonObject }

    private fun request(messages: String): AnthropicTurnBody = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","max_tokens":1000,"messages":[$messages],"tools":[
            {"name":"run","description":"run","input_schema":{"type":"object","properties":{}}}]}""",
    )

    private fun reasoningIds(input: List<JsonObject>): List<String> = input.filter {
        it["type"]?.jsonPrimitive?.content == "reasoning"
    }.map { it.getValue("id").jsonPrimitive.content }

    private fun reasoning(id: String, index: Int = 0): List<JsonObject> = listOf(
        event(
            """{"type":"response.output_item.done","output_index":$index,"item":
            {"type":"reasoning","id":"$id","encrypted_content":"fixture-$id","summary":[]}}""",
        ),
    )

    private fun prose(text: String): List<JsonObject> {
        val item = """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"$text"}]}"""
        return listOf(
            event("""{"type":"response.output_item.added","output_index":1,"item":$item}"""),
            event("""{"type":"response.output_text.delta","output_index":1,"delta":"$text"}"""),
            event("""{"type":"response.output_item.done","output_index":1,"item":$item}"""),
        )
    }

    private fun call(id: String, index: Int = 1): List<JsonObject> {
        val item = """{"type":"function_call","call_id":"$id","name":"run","arguments":"{}"}"""
        return listOf(
            event("""{"type":"response.output_item.added","output_index":$index,"item":$item}"""),
            event("""{"type":"response.output_item.done","output_index":$index,"item":$item}"""),
        )
    }

    private fun text(value: String): String = """{"type":"text","text":"$value"}"""
    private fun tool(id: String): String = """{"type":"tool_use","id":"$id","name":"run","input":{}}"""
    private fun assistant(blocks: String): String = """{"role":"assistant","content":[$blocks]}"""
    private fun result(id: String, value: String): String =
        """{"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"$value"}]}"""

    private fun imageResult(data: String): String =
        """{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":[
            {"type":"text","text":"same caption"},
            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"$data"}}]}]}"""

    private fun event(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
